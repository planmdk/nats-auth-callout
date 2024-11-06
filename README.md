# NATS Auth Callout service for OIDC JWTs

This repository contains an auth callout service for NATS which can
read and verify an OIDC-issued JWT and grant permissions based on the
roles set there.

A local development setup can be found in the `infrastructure/`
folder. See the [infrastructure README](infrastructure/README.md) for
instructions on how to run that.

See [Caveats](#Caveats).

See [NATS auth callout documentation](https://docs.nats.io/running-a-nats-service/configuration/securing_nats/auth_callout).

## Quickstart

### 1. Spin up NATS with auth callout running
If you just want to spin up NATS with auth callout, go to the
`infrastructure/` folder and follow the instructions in the [README
there](infrastructure/README.md).

### 2. Retrieve a JWT to test with
When you have done that, edit the `token_extract.py` script and set
the `client_id` and `authority` arguments to the
`PublicClientApplication`. The values are individual, you will likely
find them in your OIDC application registration (e.g. Azure Portal).

Now create a python virtual env:

`python -m venv natsauth`

And activate it with:

`source natsauth/bin/activate`

With this done, install the MSAL dependency and execute the script:

`python -m pip install msal`

`python token_extract.py`

This *should* print a token to your console. If it does not, maybe you
need to edit your application registration: create a desktop
application platform with a http://localhost:8081 redirect url and
enable public flows (might be Azure AD specific).

### 3. Use the JWT as the password for NATS

`podman run --pod=nats --rm -it --restart=no -v ./vols/nsc:/nsc:Z docker.io/natsio/nats-box:latest nats --user foo --password $JWT_TOKEN pub test 'gello'`

## Running

The auth callout is published as an OCI image to
[ghcr.io](https://ghcr.io/planmdk/nats-auth-callout) under `latest`
tag. It assumes that a `config.edn` file with the configuration is
mounted into the image at the path `/etc/app/config.edn`.

### Configuration

- `:nats-url` : [string] where to find the NATS cluster
- `:service-name` : [string] what to call the service
- `:service-version` : [string] semantic versioning!
- `:connection` : [map] object with `:username` and `:password` keys that match the NATS cluster's auth callout user
- `:oidc-endpoint-url` : [string] URL to an OIDC discovery endpoint
- `:user-keys` : [map] object with `:nseed` key
- `:application-account` : [string] name of the NATS account that users will be associated with
- `:role-mappings` : [map] object with keys matching values in the `roles` array of the OIDC JWT, and values being objects with keys `:pub` and `:sub` (see [sample config.edn](infrastructure/vols/etc-callout/config.edn) for example - `:allow` and `:deny`)
