#!/usr/bin/env bash
# Public demo from this Mac: esep-api in Docker + a Cloudflare quick tunnel + esep-web on GitHub Pages.
#
#   scripts/public-demo.sh          start (or restart) everything and point the site at the new tunnel URL
#   scripts/public-demo.sh status   show what is running
#   scripts/public-demo.sh stop     stop the tunnel and the containers
#
# Needs: Docker Desktop, cloudflared (brew install cloudflared), git with GitHub credentials
# (or GITHUB_TOKEN with repo + workflow scopes). State lives in ~/.esep-demo, outside the repo.
# The quick tunnel gets a NEW URL on every start, so the site is rebuilt each time (~2-4 min).
set -euo pipefail

REPO_WEB="Bakhyzh/esep-web"
PAGES_URL="https://bakhyzh.github.io/esep-web/"
PAGES_ORIGIN="https://bakhyzh.github.io"
API_PORT=8081
STATE="$HOME/.esep-demo"
API_DIR="$(cd "$(dirname "$0")/.." && pwd)"

log() { printf '\033[1m==>\033[0m %s\n' "$*"; }
die() { printf '\033[31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

compose() {
  docker compose --project-directory "$API_DIR" -f "$API_DIR/docker-compose.yml" -f "$STATE/override.yml" "$@"
}

stop_tunnel() {
  if [[ -f "$STATE/tunnel.pid" ]] && kill -0 "$(cat "$STATE/tunnel.pid")" 2>/dev/null; then
    kill "$(cat "$STATE/tunnel.pid")" && log "tunnel stopped"
  fi
  rm -f "$STATE/tunnel.pid"
}

github_token() {
  if [[ -n "${GITHUB_TOKEN:-}" ]]; then echo "$GITHUB_TOKEN"; return; fi
  printf 'protocol=https\nhost=github.com\n\n' | git credential fill 2>/dev/null | sed -n 's/^password=//p'
}

gh_api() {   # gh_api METHOD PATH [JSON]
  curl -fsS -X "$1" -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" \
       "https://api.github.com/repos/$REPO_WEB$2" ${3:+-d "$3"}
}

prepare_state() {
  mkdir -p "$STATE"
  chmod 700 "$STATE"
  # the default JWT_SECRET in docker-compose.yml is public: a public API must use its own
  if [[ ! -s "$STATE/jwt_secret" ]]; then
    openssl rand -hex 32 > "$STATE/jwt_secret"
    chmod 600 "$STATE/jwt_secret"
    log "generated a JWT secret in $STATE/jwt_secret"
  fi
  # CORS for the Pages origin (docker-compose.yml does not pass CORS_ALLOWED_ORIGINS into the container)
  cat > "$STATE/override.yml" <<EOF
services:
  app:
    environment:
      CORS_ALLOWED_ORIGINS: $PAGES_ORIGIN,http://localhost:5173
EOF
}

start_docker() {
  if ! docker info >/dev/null 2>&1; then
    log "starting Docker Desktop"
    open -a Docker
    for _ in $(seq 1 60); do docker info >/dev/null 2>&1 && break; sleep 3; done
    docker info >/dev/null 2>&1 || die "Docker did not start"
  fi
}

start_api() {
  log "starting esep-api (postgres, redis, kafka, app)"
  JWT_SECRET="$(cat "$STATE/jwt_secret")" compose up -d ${BUILD:+--build}
  for _ in $(seq 1 60); do curl -sf "localhost:$API_PORT/actuator/health" >/dev/null && break; sleep 3; done
  curl -sf "localhost:$API_PORT/actuator/health" >/dev/null || die "API is not healthy: docker logs esep-api"
  log "API is up on http://localhost:$API_PORT"
}

start_tunnel() {
  stop_tunnel
  : > "$STATE/tunnel.log"
  nohup cloudflared tunnel --no-autoupdate --url "http://localhost:$API_PORT" >> "$STATE/tunnel.log" 2>&1 &
  echo $! > "$STATE/tunnel.pid"
  # keep the Mac awake while the tunnel runs (a sleeping Mac = a dead site)
  nohup caffeinate -is -w "$(cat "$STATE/tunnel.pid")" >/dev/null 2>&1 &

  URL=""
  for _ in $(seq 1 30); do
    URL=$(grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' "$STATE/tunnel.log" | head -1 || true)
    [[ -n "$URL" ]] && break
    sleep 2
  done
  [[ -n "$URL" ]] || die "no tunnel URL, see $STATE/tunnel.log"
  echo "$URL" > "$STATE/tunnel.url"
  log "tunnel: $URL (waiting until it is reachable)"
  for _ in $(seq 1 40); do curl -sf -m 10 "$URL/actuator/health" >/dev/null && break; sleep 4; done
  curl -sf -m 10 "$URL/actuator/health" >/dev/null || die "tunnel is not reachable yet, try again"

  local allowed
  allowed=$(curl -s -D - -o /dev/null -X OPTIONS "$URL/api/auth/login" -H "Origin: $PAGES_ORIGIN" \
    -H "Access-Control-Request-Method: POST" -H "Access-Control-Request-Headers: content-type" \
    | tr -d '\r' | grep -i '^access-control-allow-origin:' | sed 's/^[^:]*: *//' || true)
  [[ "$allowed" == "$PAGES_ORIGIN" ]] || die "CORS does not allow $PAGES_ORIGIN (got '$allowed')"
  log "CORS allows $PAGES_ORIGIN"
}

deploy_site() {
  TOKEN=$(github_token)
  [[ -n "$TOKEN" ]] || die "no GitHub token: set GITHUB_TOKEN or log in with git"
  log "VITE_API_URL = $URL"
  gh_api PATCH /actions/variables/VITE_API_URL "{\"name\":\"VITE_API_URL\",\"value\":\"$URL\"}" >/dev/null
  local before run status
  before=$(gh_api GET "/actions/workflows/deploy.yml/runs?per_page=1" | python3 -c 'import json,sys;r=json.load(sys.stdin)["workflow_runs"];print(r[0]["id"] if r else 0)')
  gh_api POST /actions/workflows/deploy.yml/dispatches '{"ref":"main"}' >/dev/null
  log "deploy started, waiting for GitHub Actions"
  run=$before
  for _ in $(seq 1 30); do
    run=$(gh_api GET "/actions/workflows/deploy.yml/runs?per_page=1" | python3 -c 'import json,sys;print(json.load(sys.stdin)["workflow_runs"][0]["id"])')
    [[ "$run" != "$before" ]] && break
    sleep 3
  done
  for _ in $(seq 1 60); do
    status=$(gh_api GET "/actions/runs/$run" | python3 -c 'import json,sys;r=json.load(sys.stdin);print(r["status"], r["conclusion"])')
    [[ "$status" == completed* ]] && break
    sleep 10
  done
  [[ "$status" == "completed success" ]] || die "deploy: $status, see https://github.com/$REPO_WEB/actions/runs/$run"

  log "waiting for GitHub Pages to serve the new build"
  local js
  for _ in $(seq 1 40); do
    js=$(curl -s -H 'Cache-Control: no-cache' "$PAGES_URL?v=$RANDOM" | grep -o 'assets/index-[^"]*\.js' | head -1 || true)
    [[ -n "$js" ]] && curl -s "$PAGES_URL$js" | grep -q "$URL" && break
    js=""
    sleep 15
  done
  [[ -n "$js" ]] || die "Pages still serves the old build; wait a few minutes and reload"
}

case "${1:-start}" in
  start)
    command -v cloudflared >/dev/null || die "install cloudflared: brew install cloudflared"
    prepare_state
    start_docker
    start_api
    start_tunnel
    deploy_site
    log "done: ${PAGES_URL}#/login  (API $URL)"
    log "keep this Mac on; stop with: scripts/public-demo.sh stop"
    ;;
  status)
    if [[ -f "$STATE/tunnel.pid" ]] && kill -0 "$(cat "$STATE/tunnel.pid")" 2>/dev/null; then
      echo "tunnel: running, $(cat "$STATE/tunnel.url" 2>/dev/null)"
    else
      echo "tunnel: stopped"
    fi
    curl -sf "localhost:$API_PORT/actuator/health" >/dev/null && echo "api: up" || echo "api: down"
    ;;
  stop)
    stop_tunnel
    [[ -f "$STATE/override.yml" ]] && docker info >/dev/null 2>&1 && compose stop && log "containers stopped"
    ;;
  *)
    die "usage: $0 [start|status|stop]"
    ;;
esac
