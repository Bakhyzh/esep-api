# Analytics Service

Java 21 / Spring Boot 4.1.1; independently built, started and migrated. Owns only
`analytics_db`: immutable posting facts, account metadata, identity projection,
deduplication markers and durable cache generations. No credentials and no
SQL access to another service's database.

## Contracts

Public endpoints preserve the monolith's `/api/analytics/spending`,
`/top-transactions`, `/moving-average`, `/monthly-comparison` query parameters,
error responses, exact BigDecimal totals and PostgreSQL calendar/time zone rules.
A USER can query their reports; the existing explicit ADMIN reporting privilege
is retained. Each request independently validates RS256, issuer, audience,
expiration, permitted client roles and the local issuer/subject identity binding.

Spending means DEBIT postings on that user's accounts in the selected currency.
There are no category reports: the source has no category field. Reports are
eventually consistent. A just-committed transfer can precede its report update.

Consumes `esep.ledger.postings.v1` (full immutable two-sided postings, deposits
included), `esep.ledger.accounts.v1` (versioned account snapshots), and
`esep.users.v1` (identity lifecycle). JSON schemas live in `contracts/events/`.
Amounts travel as four-decimal strings and remain NUMERIC(19,4) in PostgreSQL.
No exchange-rate conversions or floating-point money arithmetic.

Event IDs deduplicate deliveries; immutable transaction/posting IDs and hashes
also deduplicate a historical export with a different event ID. Conflicting IDs
or account ownership are rejected. Account versions tolerate delayed events.
The processed marker, postings and cache generation commit in one local
transaction. Redis keys include the database generation, so an unavailable Redis
cannot lose invalidation. Redis errors fall back to authoritative read-model SQL.
Redis is a disposable cache, never a balance authority.

Kafka delivery is at least once, with three bounded retries and a service-specific
`.analytics.DLT`. Operators must investigate DLTs before replay. Source outbox
publishers may reorder records with multiple instances; the projection does not
rely on cross-topic or global ordering. Kafka is private PLAINTEXT in this local
stack; authenticated producer ACLs/TLS are a production requirement.

## Run and verify

From repository root:

```sh
./mvnw -B -f services/analytics-service/pom.xml verify
# First installation only; fails safely if credentials already exist:
python3 infrastructure/platform/init-env.py
# Build separately before start, to avoid competing image exporters:
docker compose -f compose.platform.yaml --env-file .env.platform build analytics ledger
docker compose -f compose.platform.yaml --env-file .env.platform up -d --no-build --wait
```

On an existing synthetic platform volume, add the new Analytics/Redis variables
from `.env.example` using random private values, recreate the PostgreSQL container
with the same volume, and execute the idempotent init script as bootstrap:

```sh
docker compose -f compose.platform.yaml --env-file .env.platform up -d --no-build --wait postgres kafka redis
docker compose -f compose.platform.yaml --env-file .env.platform exec -T postgres \
  psql -X -U platform_bootstrap -d postgres -v ON_ERROR_STOP=1 \
  -f /docker-entrypoint-initdb.d/10-platform.sql
```

No database host port is published. Analytics' loopback debug API is port18085;
Actuator health/readiness is port8085 internally. The independent container runs
as a non-root user. Optional Redis failure does not mark SQL reports unavailable.

## Controlled historical backfill and reconciliation

This procedure is an explicit write pause, not an automatic production migration.
Use only an approved source and a short-lived ADMIN access token in memory.
Never place it in a shell history, file or log. Internal endpoints are not public
Gateway routes. The Analytics source URL is fixed server configuration; no
caller-provided URLs or financial facts are accepted.

1. POST Ledger `/internal/ledger/write-fence` with `{"enabled":false}`. The exclusive
   PostgreSQL advisory lock waits for all in-flight financial writes. All replicas
   check the same database fence before account and transaction mutations.
2. Record the returned `epoch`. New financial writes return503; reads continue.
3. POST Analytics `/internal/analytics/backfill?epoch=<epoch>`. It reads ordered,
   bounded pages from Ledger's accounts/postings export, forwards the validated
   caller token, and applies each fact in its own local transaction. There are no
   remote identity calls inside money transactions. The source must remain fenced
   at this epoch for every page and the final check.
4. Compare Ledger `/internal/ledger/reconciliation?epoch=<epoch>` and Analytics
   `/internal/analytics/reconciliation`: every account, business owner, currency,
   exact four-decimal balance and entry count must match. Investigate discrepancies;
   do not overwrite authoritative data. Unfencing/refencing invalidates old epochs.
5. Finally POST `{"enabled":true}` to the Ledger fence and verify normal writes.

An interrupted backfill can be rerun safely; existing facts remain immutable.
A replay checks already processed hashes; it never truncates data or remaps users.
These internal APIs require network restriction and operational approval for
non-synthetic data. A distributed backup/recovery plan is a later operations
phase; this procedure does not claim a globally atomic multi-service backup.

Rollback: stop Analytics consumers/API and route reports back to the old monolith
only if it still owns the matching financial dataset. Keep the source outbox and
its new additive columns/migrations; never run two writers for the same accounts.
Always clear an intentional write fence after investigation. Ledger financial
operations and the original Notification transfer contract remain independent.
