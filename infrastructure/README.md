# Running

In the `infrastructure/` folder:

1. `distrobox-host-exec podman kube play local-dev.yaml`
2. `distrobox-host-exec podman run --pod=nats --rm -it --restart=no -v ./vols/nsc:/nsc:Z docker.io/natsio/nats-box:latest nats sub ">" --user auth --password auth`
3. `distrobox-host-exec podman run --pod=nats --rm -it --restart=no -v ./vols/nsc:/nsc:Z docker.io/natsio/nats-box:latest nats --user foo --password bar pub test 'gello'`

## What is now running?

1. Starts a NATS server using auth callout functionality
2. Starts a Keycloak server (for testing AD integration)
3. Listens on all events sent to NATS for debugging
4. Publishes an authentication request using NATS
