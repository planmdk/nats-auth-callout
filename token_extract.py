# Script to acquire a token for use with manual testing. Remember to set the client_id and authority below.

def acquire_id_token() -> str:
    from msal import ConfidentialClientApplication
    from msal import PublicClientApplication

    # REMEMBER: Make you app registration allow public flows in Azure AD
    # REMEMBER: If it still does not work, add a Mobile and Desktop Applications platform with localhost as redirect url
    app = PublicClientApplication(
    # app = ConfidentialClientApplication(
        client_id = "",
        authority="")

    result = None
    id_token = None
    # This if-state tries to aquire the token 'silently' from cache, just to limit auth-flow prompts.
    accounts = app.get_accounts()
    if accounts:
        chosen = accounts[0] # Assuming that people are only logged on to 1 account (their Vestas account)
        result = app.acquire_token_silent([""], account=chosen)

    if not result:
        # So no suitable token exists in cache. Let's get a new one from the AAD (microsoft entra).
        result = app.acquire_token_interactive(scopes=["email"])
        # result = app.acquire_token_on_behalf_of(user_assertion = "abcdef", scopes = ["email"])
    if "id_token" in result:
        id_token = result["id_token"] # The Modularisation solution requires the ID token, to retrieve which permissions the user has been granted. If you decode the JWT token, you will see the permissions in the payload. (https://jwt.ms/) this might come in handy, for explaining why the user is not able to access certain data.
        print(f'token: {id_token}')
    else:
        print(result.get("error"))
        print(result.get("error_description"))
        print(result.get("correlation_id"))  # You may need this when reporting a bug

    return id_token

acquire_id_token()
