# Running

In the `infrastructure/` folder:

1. `podman kube play local-dev.yaml`
2. `podman run --pod=nats --rm -it --restart=no -v ./vols/nsc:/nsc:Z docker.io/natsio/nats-box:latest nats sub ">" --user auth --password auth`
3. `podman run --pod=nats --rm -it --restart=no -v ./vols/nsc:/nsc:Z docker.io/natsio/nats-box:latest nats --user foo --password bar pub test 'gello'`

## What is now running?

* Starts a NATS server using auth callout functionality
* Starts an instance of this repository to handle callout (see [the sample config](vols/etc-callout/config.edn) for the config used)
* Listens on all events sent to NATS for debugging
* Publishes an authentication request using NATS (will fail!)
