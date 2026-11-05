#!/usr/bin/env python3
"""Run Spring integration with a real local Keycloak Code+PKCE token; no password grant.

Only the temporary identity is created in Keycloak. Business data/mapping use
JUnit Testcontainers, never the existing ESEP PostgreSQL. Tokens stay in memory/env.
"""
import argparse
import json
import os
from pathlib import Path
import runpy
import secrets
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--identity-dir", type=Path, default=Path(__file__).resolve().parent,
                        help="Phase 2.1 directory holding the ignored local .env")
    args = parser.parse_args()
    helper = runpy.run_path(str(Path(__file__).with_name("validate.py")))
    dc = helper["DC"]
    dc[dc.index("--env-file") + 1] = str(args.identity_dir.resolve() / ".env")
    model = json.loads(subprocess.check_output(dc + ["config", "--format", "json"], text=True))
    admin = helper["admin_login"](model["services"]["keycloak"]["environment"])
    _, api, roles = helper["verify_configuration"](admin)
    base = helper["BASE"]
    request = helper["request"]
    username = "phase22-check-" + secrets.token_hex(8)
    password = secrets.token_urlsafe(32)
    status, headers, _ = request("POST", base + "/admin/realms/esep/users", dict(
        username=username, enabled=True, firstName="Local", lastName="Integration",
        email=username + "@example.invalid", emailVerified=True,
        credentials=[dict(type="password", value=password, temporary=False)]), token=admin)
    helper["check"](status == 201, "Could not create temporary phase22 identity")
    user_url = headers["Location"]
    try:
        status, _, _ = request("POST", user_url + "/role-mappings/clients/" + api["id"],
                               [roles["USER"]], token=admin)
        helper["check"](status == 204, "Could not assign USER to temporary identity")
        tokens = helper["login"]("esep", "esep-web", helper["CALLBACK"], username, password)
        env = dict(os.environ, ESEP_KEYCLOAK_LIVE="true", ESEP_KEYCLOAK_ACCESS_TOKEN=tokens["access_token"])
        root = Path(__file__).resolve().parents[2]
        result = subprocess.run([str(root / "mvnw"), "-B", "-Dtest=KeycloakLiveIntegrationTest", "test"],
                                cwd=root, env=env)
        if result.returncode:
            raise RuntimeError("Spring live integration failed; do not publish")
        print("PASS: real Keycloak Code+PKCE -> Spring decoder -> explicit local identity -> ownership", flush=True)
    finally:
        admin = helper["admin_login"](model["services"]["keycloak"]["environment"])
        status, _, _ = request("DELETE", user_url, token=admin)
        helper["check"](status in (204, 404), "Temporary identity cleanup failed")
        print("Temporary phase22 Keycloak identity removed; no ESEP database was used.", flush=True)


if __name__ == "__main__":
    main()
