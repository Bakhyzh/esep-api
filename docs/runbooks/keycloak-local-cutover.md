# Local Keycloak cutover and activation

This procedure prepares an isolated **local** environment. Production cutover is
blocked by the application profile guard. No public deployment is authorized.
Keep the existing ESEP and Keycloak volumes. Do not run `down -v`, Flyway clean,
repair, user renumbering or balance rewrites.

## Environments

| Environment | Configuration |
|---|---|
| Legacy rollback | Existing dev/prod configuration; existing financial volumes unchanged |
| Prepared local | `local-keycloak` includes keycloak-integration without dev; excludes dev Flyway seed; enables safe new-user provisioning |
| Automated tests | test; keycloak-integration added by cryptographic tests; disposable PostgreSQL/Redis/Kafka |
| Production | Existing prod remains unchanged; prod + keycloak-integration is rejected |

The local profile enables RS256 only, strict issuer and esep-api audience, exp/nbf
validation (Spring default 60-second skew), access-token typ and permitted roles
from resource_access.esep-api. HS256 never falls back into this decoder.
Legacy register/login HTTP routes are denied; `/api/auth/me` retains numeric IDs.
Legacy encoder/service beans remain solely for controlled rollback and existing
regression fixtures; they do not provide an accepted local token path.
DemoDataSeeder and the legacy password-endpoint rate limiter are excluded from
OIDC mode; Keycloak brute-force policy protects its own login.

## Start the prepared environment

1. Start the existing local identity stack with `keycloak.sh start`. Reuse its
   ignored .env and named volume; do not regenerate credentials on an existing DB.
   Run the existing basic-scope migration if needed (see Phase 2.2 runbook).
2. `docker compose -f docker-compose.keycloak-local.yml config -q`
3. `docker compose -f docker-compose.keycloak-local.yml up -d --build --wait`
4. In the verified esep-web repository: `npm ci`, then
   `VITE_AUTH_MODE=keycloak npm run dev -- --host localhost`.
5. Open http://localhost:5173/#/login and choose Continue to sign in.

The Compose project is esep-cutover-local: isolated pgdata/Kafka volumes, loopback
API 8081, PostgreSQL 55433, Redis 56379, Kafka 59094. It never mounts the original
esep-api financial volume. Local development credentials are explicit demo values
in the inherited Compose file; this file must never be used for production.
Keycloak is an external dependency on esep-identity-dev_identity. API issuer stays
http://localhost:8180/realms/esep; container JWKS uses keycloak:8080.

Alternatively start only postgres/redis/kafka and run the JVM on the host:

```bash
DB_URL=jdbc:postgresql://localhost:55433/esep_oidc DB_USERNAME=esep DB_PASSWORD=esep \
REDIS_PORT=56379 KAFKA_BOOTSTRAP_SERVERS=localhost:59094 \
SPRING_PROFILES_ACTIVE=local-keycloak ./mvnw spring-boot:run
```

Startup waits for healthy database/Redis/Kafka in Compose. Flyway/DDL mismatch or
DB failure prevents startup; requests cannot bypass identity lookup if SQL fails.
JWKS is lazy: startup alone does not prove the IdP is ready. Unavailable JWKS for
an unknown key fails authentication; a previously cached public key can continue
to validate unexpired tokens while Keycloak is unavailable. Keycloak readiness is
on its private management port; `/actuator/health` is the API health endpoint.
Check both health and a real authenticated `/me` before enabling client traffic.

## Existing verified users

Use the independently verified owner procedure in
[keycloak-identity-migration.md](keycloak-identity-migration.md). Do not infer
ownership from matching email. Preserve original user IDs and hashes.
The repeatable executable procedure is
[link-existing-user.sql](../../infrastructure/keycloak/link-existing-user.sql):

```bash
psql -X -v ON_ERROR_STOP=1 \
  -v local_user_id=123 -v issuer=http://localhost:8180/realms/esep \
  -v subject=TRUSTED_KEYCLOAK_USER_UUID -v approved_operator=TICKET/operator \
  -v apply=false -f infrastructure/keycloak/link-existing-user.sql
```

Use approved connection credentials externally (PGSERVICE/PGPASSFILE). Review the
returned mapping and baseline account/ledger/idempotency snapshots; then repeat
with `apply=true`. Identical mappings are a no-op; a conflict stops the transaction.
Retain ticket, independently verified owner evidence, mapping ID, linked_by/time,
backup/restore evidence, before/after totals, `/me` numeric ID and ownership checks.
The script locks the user; unique constraints arbitrate concurrent conflicting
operators. Never replace or reassign a mapping automatically.

New permitted-role identities provision only when email_verified is true and the
normalized email is unused. Existing email collisions return 403 and require the
operator process. No ADMIN is created locally; Keycloak ADMIN assignment requires
an explicit authorized operator. Human provisioning rejects @esep.internal;
system funding principal is never linkable. New profiles have NULL password_hash.
Concurrent first logins serialize per issuer/sub with a PostgreSQL transaction
advisory lock; creation and mapping commit together. Different subjects racing
with the same email cannot share a user. Default integration mode keeps automatic
provisioning disabled; only the prepared local profile opts in.

## Password activation

BCrypt hashes stay untouched; no decryption, plaintext export or hash copying.
After independent ownership verification, create the Keycloak identity with no
permanent password. Record approved USER role and exact subject, then link it.
For a local operator-assisted activation, set a random temporary credential in the
trusted Admin Console, deliver it through an independently verified private channel,
and require UPDATE_PASSWORD. User must choose a new password at Keycloak before
receiving the frontend code callback. Revoke unused temporary credentials promptly;
never use shared/default passwords. The browser acceptance test exercises the
UPDATE_PASSWORD screen with random temporary test credentials, then signs in with
the changed password. The application never sees these credentials.

For production, first configure and test authenticated TLS SMTP and sender policy.
Use Keycloak Admin Console Send reset email or the authenticated Admin REST endpoint
`PUT /admin/realms/esep/users/{verified-sub}/execute-actions-email` with body
`["UPDATE_PASSWORD"]`, `lifespan=900`, and the explicitly registered client/redirect.
Deliver action links only through verified ownership channels; never log/export
them. Test expiration, single use, wrong-user rejection and delivery before real
activation. SMTP delivery/action-link expiry is **not validated locally**; do not
mark production activation ready. See [official Admin API](https://www.keycloak.org/docs-api/latest/rest-api/index.html).

## Clients and tools

esep-web uses supported keycloak-js, standard Code + PKCE S256, state/nonce and
query callback http://localhost:5173/oidc/callback. Tokens remain in adapter memory;
legacy localStorage credentials are removed. Refresh occurs before authenticated
API calls; refresh failure clears the session, without logging tokens. The callback
is processed before React HashRouter. Logout ends the Keycloak browser session.
An unlinked identity's API 403 remains an error, never an automatic email link.
Public GitHub Pages builds stay in legacy mode until separately approved.

OpenAPI in OIDC mode omits password endpoints and describes Code + PKCE.
Swagger's redirect URI is deliberately not allowed by the local client; either
paste an acquired access token into bearerAuth or approve a separate public client
with the exact Swagger redirect. The separate Keycloak Postman collection similarly
requires a dedicated public esep-postman client with the exact Postman callback,
S256, esep-api audience/client roles and no password/implicit grants. Do not broaden
esep-web redirects or export acquired tokens. Tool redirects are not runtime-tested.

## Verification and rollback

```bash
./mvnw -B verify
python3 infrastructure/keycloak/rollback-smoke.py
python3 infrastructure/keycloak/backend-smoke.py --identity-dir /path/to/original/infrastructure/keycloak
python3 infrastructure/keycloak/cutover-smoke.py \
  --identity-dir /path/to/original/infrastructure/keycloak \
  --frontend-dir /path/to/esep-web-keycloak
```

Cutover smoke only writes to esep-cutover-postgres and the local esep realm. It
retains financial fixtures as an audit trail, removes temporary Keycloak users and
its temporary signing provider, and restarts only Keycloak. Playwright disables
traces/screenshots/video to avoid saving credentials/tokens. Interrupted runs may
leave phase23-* test identities/providers; review individually, never bulk delete.

For rollback, stop the cutover API/frontend; keep all volumes and V8 mappings.
Restart the same build with the prior explicit legacy profiles and matching legacy
frontend. Use original legacy environment connection settings, never point it at a
new DB accidentally. Existing BCrypt users can still log in; Keycloak-only NULL
password users cannot. Do not claim full fallback for these users or assign random
legacy passwords: keep Keycloak operational or use a forward fix. No financial
rollback/restore is needed for an authentication-only rollback. Before real cutover,
rehearse on a restored copy with snapshots and fresh idempotent replay checks.

JWTs are stateless: logout and role removal prevent session refresh/new grants,
but issued access tokens retain their original privileges until expiry plus skew
(300+60 seconds here). For urgent revocation disable the approved mapping/traffic
through a reviewed operational process or implement token introspection/denylisting
in a separate change. Retain old signing public keys for the maximum token window;
new kid triggers JWKS refresh. Removing a key is not a reliable logout mechanism
because APIs may still cache its public key.

## Production gates (no deployment in this phase)

- Separate reviewed prod OIDC configuration/code guard change, immutable HTTPS issuer,
  correct hostname/proxy trust and exact HTTPS frontend/redirect/CORS origins.
- Optimized Keycloak image and `start --optimized`, never start-dev; TLS, private
  admin/management endpoints, MFA and tested readiness/liveness.
- Secret manager/external credentials, no default passwords; separate non-superuser
  identity DB role and ESEP runtime/migration roles, restricted mapping writes.
- Provisioning runtime must be allowed INSERT only as needed; operator mapping writes
  remain privileged. Existing general-purpose local credentials are not sufficient.
- Verified inventory, restored backups of both databases, activation delivery tests,
  explicit migration approvals, reconciliation and rehearsed rollback including
  Keycloak-only identities. No automatic production profile switch.
- Backend production deploy now requires manual workflow_dispatch with
  deploy_production=true as well as DEPLOY_ENABLED=true; pushes only test/build images.

Sources: [Keycloak adapter](https://www.keycloak.org/securing-apps/javascript-adapter),
[production configuration](https://www.keycloak.org/server/configuration),
[health](https://www.keycloak.org/observability/health),
[PostgreSQL advisory locks](https://www.postgresql.org/docs/17/explicit-locking.html).
