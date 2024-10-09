FROM clojure:tools-deps

COPY . /usr/src/app
WORKDIR /usr/src/app

CMD ["/usr/local/bin/clojure", "-M", "-m", "dk.planm.nats-auth.main", "--config", "/etc/app/config.edn"]