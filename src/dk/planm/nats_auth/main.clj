(ns dk.planm.nats-auth.main
  (:require
   [clojure.data.json :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.github.sikt-no.clj-jwt :as clj-jwt]
   [io.pedestal.log :as log])
  (:import
   [io.nats.client Nats Options]
   [io.nats.jwt
    AuthorizationResponse
    Claim
    ClaimIssuer
    JwtUtils
    Permission
    UserClaim]
   [io.nats.nkey NKey]
   [io.nats.service
    Endpoint
    ServiceBuilder
    ServiceEndpoint
    ServiceMessage
    ServiceMessageHandler]))

(defn jwk-uri
  "Fetch the JWKS URI from the OIDC discovery endpoint."
  [oidc-endpoint]
  (-> oidc-endpoint
      slurp
      (json/read-str :key-fn keyword)
      :jwks_uri))

(defn respond-on
  "Create the final NATS JWT and send it as a response on the original message's
  response inbox.

  Note that the user's JWT gets wrapped in an AuthorizationResponse
  object, which is put in the nats claim of the final JWT."
  [connection {:keys [msg
                      authorization-request
                      user-jwt
                      error
                      user-keys]}]
  (let [authorization-response (.. (AuthorizationResponse.)
                                   (jwt user-jwt)
                                   (error error))]
    (if user-jwt
      (log/info :msg "[HANDLER] Auth response JWT" :jwt user-jwt)
      (log/info :msg "[HANDLER] Auth response ERR" :response authorization-response))
    (let [jwt (.. (ClaimIssuer.)
                  (aud (.. authorization-request serverId id))
                  (iss (:public-key user-keys))
                  (sub (.userNkey authorization-request))
                  (nats authorization-response)
                  (issueJwt (:private-key user-keys)))]
      (log/info :msg "[HANDLER] Claim response" :response (JwtUtils/getClaimBody jwt))
      (.respond msg connection jwt))))

(defn user-permissions
  "Create a map of read/write permissions for the user given the roles
  specified in their AD JWT."
  [user-roles role-permission-mapping]
  (let [sub-allow (reduce (fn [acc role]
                            (into acc (get-in role-permission-mapping [role :sub :allow])))
                          []
                          user-roles)
        sub-deny (reduce (fn [acc role]
                           (into acc (get-in role-permission-mapping [role :sub :deny])))
                         []
                         user-roles)
        pub-allow (reduce (fn [acc role]
                            (into acc (get-in role-permission-mapping [role :pub :allow])))
                          []
                          user-roles)
        pub-deny (reduce (fn [acc role]
                           (into acc (get-in role-permission-mapping [role :pub :deny])))
                         []
                         user-roles)]
    {:pub/allow pub-allow
     :pub/deny pub-deny
     :sub/allow sub-allow
     :sub/deny sub-deny}))

(defn ad-jwt->nats-jwt
  "Translate the JWT issued by the OIDC server into a NATS JWT."
  [ad-jwt authorization-request config]
  (log/info :ad-jwt ad-jwt)
  (let [permissions (user-permissions (:roles ad-jwt) (:role-mappings config))
        user-claim (.. (UserClaim.)
                       (pub (.. (Permission.)
                                (allow (:pub/allow permissions []))
                                (deny (:pub/deny permissions []))))
                       (sub (.. (Permission.)
                                (allow (:sub/allow permissions []))
                                (deny (:sub/deny permissions [])))))]
    (.. (ClaimIssuer.)
        (aud (:application-account config "APP"))
        (name (:email ad-jwt))
        (iss (get-in config [:user-keys :public-key]))
        (sub (.userNkey authorization-request))
        (nats user-claim)
        (issueJwt (get-in config [:user-keys :private-key])))))

(defn on-message-impl
  "Because writing code inside a proxy call is not always a good idea
  with the REPL."
  [^ServiceMessage msg connection config]
  (log/info :msg "[HANDLER] Received message" :subject (.getSubject msg) :headers (.getHeaders msg))
  (let [claim (Claim. (JwtUtils/getClaimBody (.getData msg)))
        authorization-request (.authorizationRequest claim)]
    (if-not authorization-request
      (log/info :msg "Invalid Authorization Request Claim")
      (try
        (let [token (clj-jwt/unsign (:jwks-url config) (.pass (.connectOpts authorization-request)))]
          (log/info :config config :token token)
          (respond-on connection {:msg msg
                                  :authorization-request authorization-request
                                  :user-jwt (ad-jwt->nats-jwt token authorization-request config)
                                  :user-keys (:user-keys config)}))
        (catch Exception ex
          (log/error :msg (.getMessage ex))
          (respond-on connection {:msg msg
                                  :authorization-request authorization-request
                                  :user-jwt nil
                                  :user-keys (:user-keys config)}))))))

(defn auth-callout-handler
  "Create an instance of the ServiceMessageHandler interface."
  [connection config]
  (proxy [ServiceMessageHandler] []
    (onMessage [^ServiceMessage msg]
      (on-message-impl msg connection config))))

(defn auth-callout-service
  "Create and start the auth callout service."
  [config]
  (let [{:keys [nats-url service-name service-version oidc-endpoint-url user-keys]} config]
    (try
      (let [options (.. (Options/builder)
                        (server nats-url)
                        (userInfo (get-in config [:connection :username])
                                  (get-in config [:connection :password]))
                        (build))
            endpoint (.. (Endpoint/builder)
                         (name service-name)
                         (subject "$SYS.REQ.USER.AUTH")
                         (build))
            connection (Nats/connect options)
            handler (auth-callout-handler connection {:user-keys user-keys
                                                      :jwks-url (jwk-uri oidc-endpoint-url)
                                                      :application-account (:application-account config)
                                                      :role-mappings (:role-mappings config)})
            service-endpoint (.. (ServiceEndpoint/builder)
                                 (endpoint endpoint)
                                 (handler handler)
                                 (build))
            ac-service (.. (ServiceBuilder.)
                           (connection connection)
                           (name service-name)
                           (version service-version)
                           (addServiceEndpoint service-endpoint)
                           (build))]
        {:future (.startService ac-service)
         :service ac-service})
      (catch Exception ex
        (log/error :msg (.getMessage ex)
                   :stack-trace (.printStackTrace ex))))))

(defn nseed->keys
  [keys-map]
  (-> keys-map
      (assoc :private-key (-> (:nseed keys-map) char-array NKey/fromSeed))
      (assoc :public-key (-> (:nseed keys-map) char-array NKey/fromSeed .getPublicKey String.))))

(defn read-config
  [path]
  (with-open [r (io/reader path)]
    (edn/read (java.io.PushbackReader. r))))

(defn -main [& args]
  (let [cmd-opts (into {} (map vec) (partition 2 args))]
    (assert (not (str/blank? (get cmd-opts "--config"))) "--config option must be present and point to a valid config file")
    (let [config (update (read-config (get cmd-opts "--config"))
                         :user-keys nseed->keys)
          service (auth-callout-service config)]
      (.get (:future service)))))

(comment
  (def callout-service (auth-callout-service (read-config "sample-config.edn")))

  (.stop (:service callout-service))

  (log/warn :msg "foo")

  (.keySet (Thread/getAllStackTraces)))
