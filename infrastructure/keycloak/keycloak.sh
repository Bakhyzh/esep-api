#!/bin/bash
set -euo pipefail
IDENTITY_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if [[ "${1:-}" == init ]]; then
  python3 - "$IDENTITY_DIR/.env" <<'PY'
import os
import secrets
import sys
try:
    fd = os.open(sys.argv[1], os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
except FileExistsError:
    sys.exit("Local .env already exists; kept unchanged. Use the existing credentials.")
with os.fdopen(fd, "w") as env:
    for name in ("KC_DB_BOOTSTRAP_PASSWORD", "KC_DB_PASSWORD", "KC_ADMIN_PASSWORD"):
        env.write(name + "=" + secrets.token_hex(24) + "\n")
    env.write("KC_ADMIN_USERNAME=local-kc-admin\n")
print("Created local .env with independent random credentials (0600); values not printed.")
PY
  exit 0
fi
if [[ ! -f "$IDENTITY_DIR/.env" ]]; then
  echo 'Create infrastructure/keycloak/.env from .env.example and replace placeholders.' >&2
  exit 1
fi
dc() {
  docker compose --project-name esep-identity-dev --project-directory "$IDENTITY_DIR" \
    --env-file "$IDENTITY_DIR/.env" -f "$IDENTITY_DIR/compose.yaml" "$@"
}
case "${1:-help}" in
  config) dc config --quiet ;;
  start) dc up -d --wait --wait-timeout 240 ;;
  stop) dc stop ;;
  status) dc ps -a ;;
  logs) dc logs --tail 100 ;;
  restart) dc restart; dc up -d --wait --wait-timeout 240 ;;
  validate) python3 "$IDENTITY_DIR/validate.py" ;;
  persistence) python3 "$IDENTITY_DIR/validate.py" --persistence ;;
  help) echo 'Usage: bash infrastructure/keycloak/keycloak.sh {init|config|start|stop|status|logs|restart|validate|persistence}' ;;
  *) echo "Unknown command: $1" >&2; exit 2 ;;
esac
