#!/bin/sh
set -eu
for value in "${APP_HOST:?required}" "${GATEWAY_TLS_SERVER_NAME:?required}"; do
  case "$value" in ''|*[!A-Za-z0-9.-]*) echo 'Exact TLS DNS hostname required' >&2; exit 1;; esac
done
case "${OIDC_ORIGIN:?required}" in https://*) issuer_host=${OIDC_ORIGIN#https://};; *) echo 'HTTPS OIDC origin required' >&2; exit 1;; esac
case "$issuer_host" in ''|*[!A-Za-z0-9.:-]*) echo 'Exact OIDC origin required' >&2; exit 1;; esac
envsubst '${APP_HOST} ${OIDC_ORIGIN} ${GATEWAY_TLS_SERVER_NAME}' < /config/nginx.conf.template > /tmp/nginx.conf
case "${1:-}" in
  --check) exec nginx -t -q -c /tmp/nginx.conf;;
  '') exec nginx -c /tmp/nginx.conf -g 'daemon off;';;
  *) echo 'Unsupported edge argument' >&2; exit 2;;
esac
