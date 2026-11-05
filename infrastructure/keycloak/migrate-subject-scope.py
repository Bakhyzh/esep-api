#!/usr/bin/env python3
"""Explicit, idempotent local migration: attach Keycloak basic scope to esep-web.

Startup import skips existing realms. No realm/DB reset, no user migration,
no password grant. Dry-run by default; --apply is required for the sole PUT.
"""
import argparse
import json
from pathlib import Path
import runpy
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--identity-dir", type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    helper = runpy.run_path(str(Path(__file__).with_name("validate.py")))
    dc = helper["DC"]
    dc[dc.index("--env-file") + 1] = str(args.identity_dir.resolve() / ".env")
    model = json.loads(subprocess.check_output(dc + ["config", "--format", "json"], text=True))
    admin = helper["admin_login"](model["services"]["keycloak"]["environment"])
    root = helper["BASE"] + "/admin/realms/esep"
    clients = helper["get_json"](root + "/clients?clientId=esep-web", admin)
    helper["check"](len(clients) == 1 and clients[0]["clientId"] == "esep-web", "Expected exactly one local frontend client")
    scopes = helper["get_json"](root + "/client-scopes", admin)
    basic = [scope for scope in scopes if scope["name"] == "basic"]
    helper["check"](len(basic) == 1, "Built-in basic scope missing; stop for manual review")
    url = root + "/clients/" + clients[0]["id"] + "/default-client-scopes"
    assigned = helper["get_json"](url, admin)
    if any(scope["id"] == basic[0]["id"] for scope in assigned):
        print("PASS: esep-web already has basic scope; no change.")
        return
    if not args.apply:
        print("DRY RUN: would attach existing basic scope to local esep-web; use --apply after review.")
        return
    status, _, _ = helper["request"]("PUT", url + "/" + basic[0]["id"], token=admin)
    helper["check"](status == 204, "Scope migration failed")
    assigned = helper["get_json"](url, admin)
    helper["check"](any(scope["id"] == basic[0]["id"] for scope in assigned), "Scope assignment not persisted")
    print("PASS: basic scope attached to local esep-web; realm, user IDs and existing data preserved.")


if __name__ == "__main__":
    main()
