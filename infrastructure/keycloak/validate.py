#!/usr/bin/env python3
"""Local infrastructure smoke test. Stdlib only; never uses password grant.

Uses real authorization-code/PKCE exchanges via HTML login forms. Creates and
finally deletes two random, unlinked test identities in the dedicated esep realm.
Not a production OIDC client or replacement for future Spring Security tests.
"""
import argparse
import base64
import hashlib
import http.cookiejar
import json
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from html.parser import HTMLParser
from pathlib import Path

DIR = Path(__file__).resolve().parent
BASE = "http://localhost:8180"
ISSUER = BASE + "/realms/esep"
CALLBACK = "http://localhost:5173/oidc/callback"
DC = ["docker", "compose", "--project-name", "esep-identity-dev",
      "--project-directory", str(DIR), "--env-file", str(DIR / ".env"),
      "-f", str(DIR / "compose.yaml")]


def check(condition, message):
    if not condition:
        raise RuntimeError(message)


def passed(message):
    print("PASS: " + message, flush=True)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class LocalhostCookiePolicy(http.cookiejar.DefaultCookiePolicy):
    def return_ok_secure(self, cookie, req):
        # Modern browsers treat localhost as a secure context; Keycloak 26 sets
        # Secure cookies here even with local HTTP. Match that behavior only for
        # our fixed loopback origin, without weakening any Keycloak cookie setting.
        if req.type == "http" and req.host == "localhost:8180":
            return True
        return super().return_ok_secure(cookie, req)


def browser():
    return urllib.request.build_opener(
        urllib.request.ProxyHandler({}),
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar(LocalhostCookiePolicy())), NoRedirect())


def request(method, url, data=None, token=None, opener=None, form=False):
    check(url.startswith(BASE + "/"), "Refusing a non-local Keycloak URL")
    headers = {}
    if data is not None:
        if form:
            data = urllib.parse.urlencode(data).encode()
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        else:
            data = json.dumps(data).encode()
            headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        response = (opener or browser()).open(req, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, response.headers, response.read().decode()


def get_json(url, token=None):
    status, _, body = request("GET", url, token=token)
    check(status == 200, "JSON endpoint returned HTTP " + str(status))
    return json.loads(body)


class LoginForm(HTMLParser):
    def __init__(self):
        super().__init__()
        self.action = None
        self.fields = {}
        self.inside = False

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "form" and attrs.get("id") == "kc-form-login":
            self.action = attrs.get("action")
            self.inside = True
        if self.inside and tag == "input" and attrs.get("name"):
            self.fields[attrs["name"]] = attrs.get("value", "")

    def handle_endtag(self, tag):
        if tag == "form":
            self.inside = False


def b64(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def unb64(value):
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def authorization_code(realm, client, callback, username, password):
    verifier = secrets.token_urlsafe(48)
    state = secrets.token_urlsafe(24)
    params = dict(client_id=client, redirect_uri=callback, response_type="code",
                  scope="openid", state=state, nonce=secrets.token_urlsafe(24),
                  code_challenge=b64(hashlib.sha256(verifier.encode()).digest()),
                  code_challenge_method="S256")
    opener = browser()
    status, _, body = request("GET", BASE + "/realms/" + realm
                              + "/protocol/openid-connect/auth?"
                              + urllib.parse.urlencode(params), opener=opener)
    check(status == 200, "Authorization endpoint did not display login form")
    form = LoginForm()
    form.feed(body)
    check(form.action is not None, "Expected Keycloak username/password HTML form")
    form.fields.update(username=username, password=password)
    status, headers, _ = request("POST", form.action, form.fields, opener=opener, form=True)
    location = headers.get("Location", "")
    check(status in (302, 303) and location.startswith(callback + "?"),
          "Browser login did not return an authorization code (check required actions)")
    params = urllib.parse.parse_qs(urllib.parse.urlsplit(location).query)
    check(params.get("state") == [state] and "code" in params,
          "Authorization callback code/state invalid")
    return params["code"][0], verifier


def exchange(realm, client, callback, code, verifier):
    status, _, body = request("POST", BASE + "/realms/" + realm
                              + "/protocol/openid-connect/token",
                              dict(grant_type="authorization_code", client_id=client,
                                   redirect_uri=callback, code=code, code_verifier=verifier),
                              form=True)
    return status, json.loads(body)


def login(realm, client, callback, username, password):
    code, verifier = authorization_code(realm, client, callback, username, password)
    status, tokens = exchange(realm, client, callback, code, verifier)
    check(status == 200 and "access_token" in tokens, "Code + PKCE exchange failed")
    return tokens


def admin_login(env):
    # Real browser code flow of the built-in admin console, not admin-cli password grant.
    return login("master", "security-admin-console", BASE + "/admin/master/console/",
                 env["KC_BOOTSTRAP_ADMIN_USERNAME"], env["KC_BOOTSTRAP_ADMIN_PASSWORD"])["access_token"]


def verify_jwt(token, jwks, expected_roles):
    # Validation-only RS256 PKCS#1 v1.5 check against advertised public JWKS.
    # Production integration will use Spring Security/Nimbus, not this smoke helper.
    header64, payload64, signature64 = token.split(".")
    header = json.loads(unb64(header64))
    payload = json.loads(unb64(payload64))
    check(header.get("alg") == "RS256", "Unexpected signing algorithm")
    key = next(k for k in jwks["keys"] if k["kid"] == header["kid"])
    n, e = int.from_bytes(unb64(key["n"]), "big"), int.from_bytes(unb64(key["e"]), "big")
    width = (n.bit_length() + 7) // 8
    signature = unb64(signature64)
    check(len(signature) == width and int.from_bytes(signature, "big") < n, "Invalid RSA signature size")
    decoded = pow(int.from_bytes(signature, "big"), e, n).to_bytes(width, "big")
    digest = hashlib.sha256((header64 + "." + payload64).encode()).digest()
    digest_info = bytes.fromhex("3031300d060960864801650304020105000420") + digest
    expected = b"\x00\x01" + b"\xff" * (width - len(digest_info) - 3) + b"\x00" + digest_info
    check(secrets.compare_digest(decoded, expected), "RS256 signature verification failed")
    aud = payload.get("aud", [])
    if isinstance(aud, str):
        aud = [aud]
    check(payload.get("iss") == ISSUER and "esep-api" in aud, "Invalid issuer/audience")
    check(isinstance(payload.get("sub"), str) and bool(payload["sub"].strip()), "Missing access-token subject")
    check(payload.get("exp", 0) > time.time(), "Access token expired")
    check(0 < payload["exp"] - payload["iat"] <= 300, "Access token TTL exceeds 300s")
    roles = set(payload.get("resource_access", {}).get("esep-api", {}).get("roles", []))
    check(roles == set(expected_roles), "Unexpected client-role claims")


def verify_configuration(token):
    realm = get_json(BASE + "/admin/realms/esep", token)
    clients = get_json(BASE + "/admin/realms/esep/clients", token)
    clients = {c["clientId"]: c for c in clients}
    web, api = clients["esep-web"], clients["esep-api"]
    check(web["publicClient"] and web["standardFlowEnabled"], "Frontend must be public code-flow client")
    check(not web["implicitFlowEnabled"] and not web["directAccessGrantsEnabled"]
          and not web["serviceAccountsEnabled"], "Forbidden frontend grant enabled")
    check(web["attributes"].get("pkce.code.challenge.method") == "S256", "PKCE S256 not enforced")
    default_scopes = get_json(BASE + "/admin/realms/esep/clients/" + web["id"] + "/default-client-scopes", token)
    check(any(scope["name"] == "basic" for scope in default_scopes), "Missing basic scope supplying access-token sub")
    check(web["redirectUris"] == [CALLBACK] and web["webOrigins"] == ["http://localhost:5173"],
          "Unexpected redirect URI/origin")
    check(api["bearerOnly"] and not api["standardFlowEnabled"]
          and not api["directAccessGrantsEnabled"] and not api["implicitFlowEnabled"],
          "API resource client has interactive/password grants")
    roles_url = BASE + "/admin/realms/esep/clients/" + api["id"] + "/roles"
    roles = {r["name"]: r for r in get_json(roles_url, token)}
    check(set(roles) == {"USER", "ADMIN"}, "Required API roles not present")
    mappers = {m["name"]: m for m in web["protocolMappers"]}
    aud = mappers["esep-api-audience"]["config"]
    check(aud.get("included.client.audience") == "esep-api"
          and aud.get("access.token.claim") == "true" and aud.get("id.token.claim") == "false",
          "Audience mapper not correct")
    check(realm["accessTokenLifespan"] == 300 and realm["defaultSignatureAlgorithm"] == "RS256",
          "Realm TTL/signing settings incorrect")
    passed("Admin API: clients, PKCE S256, disabled password/implicit grants, roles, audience mapper, TTL")
    return realm, api, roles


def verify_auth_requests():
    common = dict(client_id="esep-web", redirect_uri=CALLBACK, response_type="code",
                  scope="openid", state="local-validation", prompt="none")
    for extra in ({}, {"code_challenge": "x" * 43, "code_challenge_method": "plain"}):
        params = dict(common, **extra)
        status, headers, body = request("GET", ISSUER + "/protocol/openid-connect/auth?"
                                        + urllib.parse.urlencode(params))
        error = urllib.parse.parse_qs(urllib.parse.urlsplit(headers.get("Location", "")).query)
        check((status in (302, 303) and error.get("error") == ["invalid_request"])
              or (status == 400 and ("code_challenge" in body or "PKCE" in body)),
              "Authorization without required S256 challenge was not rejected")
    passed("Authorization endpoint rejects missing PKCE and plain challenge")
    challenge = b64(hashlib.sha256(secrets.token_urlsafe(48).encode()).digest())
    params = dict(common, code_challenge=challenge, code_challenge_method="S256",
                  redirect_uri="http://localhost:5173/not-approved")
    status, _, _ = request("GET", ISSUER + "/protocol/openid-connect/auth?"
                            + urllib.parse.urlencode(params))
    check(status == 400, "Unapproved redirect URI accepted")
    params = dict(common, code_challenge=challenge, code_challenge_method="S256", response_type="token")
    status, headers, body = request("GET", ISSUER + "/protocol/openid-connect/auth?"
                                   + urllib.parse.urlencode(params))
    location = headers.get("Location", "")
    split = urllib.parse.urlsplit(location)
    errors = urllib.parse.parse_qs(split.fragment or split.query)
    check((status in (302, 303) and errors.get("error", [""])[0]
           in {"unauthorized_client", "unsupported_response_type", "invalid_request"})
          or (status == 400 and "Implicit" in body), "Implicit flow was not rejected")
    passed("Unapproved redirect URI and implicit token flow rejected")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--persistence", action="store_true", help="Restart only this project's containers")
    args = parser.parse_args()
    # Capture config privately: never print resolved credentials or token responses.
    model = json.loads(subprocess.check_output(DC + ["config", "--format", "json"], text=True))
    check(model["name"] == "esep-identity-dev" and set(model["services"]) == {"keycloak", "keycloak-db"},
          "Unexpected Compose project/services")
    env = model["services"]["keycloak"]["environment"]
    discovery = get_json(ISSUER + "/.well-known/openid-configuration")
    check(discovery["issuer"] == ISSUER, "Discovery has wrong canonical issuer")
    check(discovery["jwks_uri"] == ISSUER + "/protocol/openid-connect/certs", "Unexpected JWKS URI")
    jwks = get_json(discovery["jwks_uri"])
    check(any(k.get("alg") == "RS256" and k.get("kty") == "RSA" for k in jwks["keys"]), "No RSA signing key")
    passed("Realm discovery and public JWKS return HTTP 200 with canonical issuer and RS256 key")
    admin = admin_login(env)
    realm, api, roles = verify_configuration(admin)
    verify_auth_requests()
    users = []
    try:
        for assigned in (["USER"], ["USER", "ADMIN"]):
            username = "phase21-check-" + secrets.token_hex(8)
            password = secrets.token_urlsafe(32)
            status, headers, _ = request("POST", BASE + "/admin/realms/esep/users", dict(
                username=username, enabled=True, firstName="Local", lastName="Validation",
                email=username + "@example.invalid", emailVerified=True,
                credentials=[dict(type="password", value=password, temporary=False)]), token=admin)
            check(status == 201, "Could not create random temporary validation identity")
            user_url = headers["Location"]
            users.append(user_url)
            status, _, _ = request("POST", user_url + "/role-mappings/clients/" + api["id"],
                                   [roles[r] for r in assigned], token=admin)
            check(status == 204, "Could not assign temporary identity roles")
            if assigned == ["USER"]:
                code, verifier = authorization_code("esep", "esep-web", CALLBACK, username, password)
                status, result = exchange("esep", "esep-web", CALLBACK, code, "wrong-" + verifier)
                check(status == 400 and result.get("error") == "invalid_grant", "Wrong verifier accepted")
                passed("Token endpoint rejects wrong PKCE verifier (HTTP 400 invalid_grant)")
            tokens = login("esep", "esep-web", CALLBACK, username, password)
            verify_jwt(tokens["access_token"], jwks, assigned)
            id_claims = json.loads(unb64(tokens["id_token"].split(".")[1]))
            check(id_claims.get("aud") == "esep-web", "ID token audience must remain frontend")
            passed("Real code + PKCE token: RS256 signature, issuer, esep-api audience, TTL, " + "/".join(assigned))
        if args.persistence:
            marker = secrets.token_hex(16)
            attrs = dict(realm.get("attributes", {}))
            attrs["esep.phase21.persistence"] = marker
            status, _, _ = request("PUT", BASE + "/admin/realms/esep", {"attributes": attrs}, token=admin)
            check(status == 204, "Could not write realm persistence marker")
            subprocess.run(DC + ["restart"], check=True)
            subprocess.run(DC + ["up", "-d", "--wait", "--wait-timeout", "240"], check=True)
            admin = admin_login(env)
            restored, _, _ = verify_configuration(admin)
            check(restored["id"] == realm["id"]
                  and restored["attributes"].get("esep.phase21.persistence") == marker,
                  "Realm ID or runtime marker did not persist")
            for user_url in users:
                check(get_json(user_url, admin)["enabled"], "Temporary identity did not persist")
            new_jwks = get_json(discovery["jwks_uri"])
            check({k["kid"] for k in jwks["keys"]} == {k["kid"] for k in new_jwks["keys"]},
                  "Signing keys changed across restart")
            # Recreate containers too: the named DB volume must be the source of truth.
            subprocess.run(DC + ["up", "-d", "--force-recreate", "--wait", "--wait-timeout", "240"], check=True)
            admin = admin_login(env)
            restored, _, _ = verify_configuration(admin)
            check(restored["id"] == realm["id"]
                  and restored["attributes"].get("esep.phase21.persistence") == marker,
                  "Runtime marker did not survive container recreation")
            for user_url in users:
                check(get_json(user_url, admin)["enabled"], "Identity lost during container recreation")
            final_jwks = get_json(discovery["jwks_uri"])
            check({k["kid"] for k in jwks["keys"]} == {k["kid"] for k in final_jwks["keys"]},
                  "Signing keys changed during container recreation")
            passed("Both containers restarted and recreated; realm ID, runtime marker and identities persisted")
    finally:
        if users:
            admin = admin_login(env)
        for user_url in users:
            status, _, _ = request("DELETE", user_url, token=admin)
            check(status in (204, 404), "Temporary identity cleanup failed")
        if users:
            passed("Random temporary business identities removed; no hardcoded business passwords")
    passed("Local identity infrastructure validation completed; ESEP authentication not switched")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Avoid dumping HTTP bodies, credentials or full JWTs on failure.
        print("FAIL: " + (str(error) if isinstance(error, RuntimeError) else type(error).__name__), file=sys.stderr)
        sys.exit(1)
