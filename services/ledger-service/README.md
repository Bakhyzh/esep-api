# ESEP Ledger Service

Independent Java 21 / Spring Boot 4.1.1 application. It owns accounts, balances,
transactions, postings, history, financial idempotency and outbox in **one ledger_db**.
It does not own user profiles/passwords, analytics, Redis or notification consumers.
It builds from this directory with `mvn verify` or from the repository using:

```bash
./mvnw -B -f services/ledger-service/pom.xml verify
```

Its Docker build context is this directory and requires no root application sources.

## Local environment

Start the existing Keycloak identity stack first (see infrastructure/keycloak).
Use its persistent esep realm and exact issuer; do not reset/reimport an existing
realm. Ledger binds only loopback 8082; its own PostgreSQL debug port is 55434,
Kafka debug listener 59095. These do not overlap the original/cutover/db-lab ports.
Existing monolith databases/volumes and 8081 routes are not used or changed.

Generate three independent secrets into an ignored `.env` with mode 0600 (do not
replace a file for an already initialized DB; changing env does not rotate DB roles):

```bash
cd services/ledger-service
python3 - <<'PY'
import os, secrets
with os.fdopen(os.open('.env',os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600),'w') as f:
    for name in ['LEDGER_BOOTSTRAP_PASSWORD','LEDGER_MIGRATION_PASSWORD','LEDGER_DB_PASSWORD']:
        f.write(name+'='+secrets.token_urlsafe(36)+'\n')
PY
docker compose --env-file .env config -q
docker compose --env-file .env up -d --build --wait
```

Images PostgreSQL 17, Kafka 3.9.1 and Java 21 support Apple Silicon; native ARM64
execution was tested. Persistent volumes are scoped to esep-ledger-local. Never
run down -v or prune/delete existing volumes. Normal `docker compose stop` preserves
all data. No default passwords, demo human users or ADMIN roles are seeded.

The explicit local/test Flyway location adds synthetic system funding principals
and zero-balance KZT/USD/EUR accounts. Human identities must be independently
approved/materialized first. The base profile has only db/ledger V1 and does not
create synthetic system principals: real migration supplies original system IDs.

Runtime ledger_runtime has financial SELECT/INSERT/UPDATE rights as needed, but
cannot modify identity projections, delete historical entries or create schema.
ledger_migrator owns this DB/schema; ledger_bootstrap is container-init/admin only,
never passed to the app. Flyway uses distinct migrator credentials in local mode.
For production, use a separate migration job and no migration credentials in the
running API. init.sql only runs on an empty dedicated volume. Never use these
scripts against a database containing unapproved existing financial data.

## Authentication and identity

Only RS256 OAuth2 access tokens accepted, validated against Keycloak JWKS plus
exact issuer, esep-api audience, exp/nbf, Bearer typ, nonblank opaque sub and
esep-api client roles. No legacy decoder/encoder/password/login endpoint.

ledger_principals is a local immutable numeric business-ID reference, not a users
copy. ledger_identity_bindings holds exact approved issuer/subject plus operator/
time, unique both per identity and per user/issuer. Runtime resolution is read-only
and local; there is no cross-service DB access or network call in a money tx.
Unknown identity gets 403. Email/userId/header claims cannot link an account.

Use scripts/bootstrap-identity.sql as the authorized operator with psql variables
(user_id, issuer, subject, approved_operator, apply=false). Review the dry-run
mapping; apply=true commits; an identical retry is a no-op and conflicts fail.
Use original approved numeric IDs from the identity authority, not a sequence or
email matching. No public binding/provisioning endpoint exists. See the explicit
[identity and API contracts](contracts/README.md). A future User Service projection
transport is not implemented in Phase 3.1.

## Startup and failures

Compose requires healthy PostgreSQL/Kafka and the existing identity network.
Flyway validates the service-specific history; Hibernate validates entities.
JWKS fetch is lazy, so health alone is not proof of successful OIDC validation.
Smoke must obtain a real token. Unknown key/JWKS failures fail closed; cached keys
can verify unexpired JWTs during an IdP outage. Logout/role revocation is bounded
by token expiry plus Spring's 60-second skew (current realm TTL 300s).

`/actuator/health/liveness` checks application liveness.
`/actuator/health/readiness` includes DB availability; authentication is not required
and details are hidden. Kafka outage does not prevent a financial commit: event
stays pending in outbox and retries after recovery. Monitor backlog/oldest age in a
future operational phase. Kafka's local RF1 is not HA. IdP or DB failure never
converts an unknown identity into an authenticated owner.

For a host JVM set LEDGER_DB_URL, LEDGER_DB_USERNAME, LEDGER_DB_PASSWORD,
LEDGER_MIGRATION_USERNAME/PASSWORD, LEDGER_KAFKA_BOOTSTRAP_SERVERS,
OIDC_ISSUER_URI and OIDC_JWK_SET_URI externally. No implicit shared DB defaults.
Local host issuer/JWKS use localhost:8180; container issuer remains that exact URL
while JWKS uses keycloak:8080 over the explicit external identity network.

## Verification

```bash
./mvnw -B -f services/ledger-service/pom.xml verify
python3 services/ledger-service/scripts/live-smoke.py --identity-dir /path/to/original/infrastructure/keycloak
python3 services/ledger-service/scripts/restore-smoke.py
```

Run those commands from the repository root. The live smoke uses actual Keycloak
Code+PKCE, random temporary Keycloak identities and the isolated Ledger API/DB.
It tests mappings, role/ownership, financial requests and stops/restarts **only the
Ledger Kafka** to prove durable outbox recovery. No password grant or token logs.
Temporary Keycloak users are removed; financial/mapping audit fixtures remain.
The restore smoke streams a synthetic custom-format pg_dump into a new uniquely
named DB, compares all rows/history, financial invariants and next sequences.
It never overwrites/drops a DB; restore databases remain for review. This is not a
real-user monolith migration or a production DR readiness claim.

See [migration and rollback](../../docs/runbooks/ledger-extraction-migration.md).
Frontend/API routing still targets the monolith; no traffic switch is performed.
Before production require reviewed real-data restore/import/reconciliation, a
single authoritative writer, tested identity bootstrap, HTTPS issuer/JWKS/CORS,
TLS/ACL Kafka, external secrets, backups and independently authorized deployment.
