# ESEP implementation and verification report

Local implementation is complete in the working branch; final CI/publication receipt
is pending. This report does not approve real customer operation or production traffic.

## Architecture and ownership

Five independent Boot4.1.1/Java21 applications live under services/: User, Ledger,
Notification, Analytics and Gateway (Cloud2025.1.4). Each has its own lifecycle,
configuration, Docker image and tests. User/Notification/Analytics/Ledger own user_db,
notification_db, analytics_db and ledger_db. Keycloak owns a separate identity DB.
Two Gateways sit behind Nginx; Ledger2 is optional on the M1 configuration.

Ledger retains one PostgreSQL financial transaction boundary for account locks,
balances, double-entry postings, idempotency and outbox. Monetary algorithms were
not rewritten. The original monolith and V1–V8 remain available and were not made
writers against Ledger accounts. No production data migration was performed.

User profile IDs and verified issuer/subject bindings are authoritative in User.
Transactional identity events feed restricted service-local projections. Email and
client-provided IDs do not grant ownership. System accounts are separate. Financial
and identity events have stable IDs, explicit v1 contracts and idempotent consumers.
Delivery is at least once, not proven exactly once.

## Security and runtime hardening

Actual Code+PKCE/JWKS RS256 flow works through the verified frontend and Gateway.
Every service independently checks issuer/audience/time/roles and account ownership.
USER/ADMIN roles come from esep-api; ADMIN cannot spend another owner's source.
HS256 tokens are rejected by the platform. Credentials/tokens are not logged.

Kafka SASL principals and exact producer/topic/group ACLs reject identity/fact forgery.
Local SASL_PLAINTEXT is private development configuration; production requires
SASL_SSL/hostname verification. Old writable-layer Kafka logs were encrypted,
verified and copied into persistent storage. Kafka4.3.2 security fixes passed a
clone and actual rollback test, then local cutover with unchanged offsets and
metadata3.9-IV0. No metadata feature upgrade was automatically finalized.

Keycloak26.8.2 runs against a separately restored/validated identity database.
Existing user IDs, roles, human credential material, signing keys and operator
issuer/subject were checked on the clone. The original keycloak_db is retained.
The earlier final startup ended143; the resumed2026-11-17 lifecycle/start and full
browser E2E passed. The selection file is private and never committed.

Security backports: Tomcat11.0.27, Jackson3.1.7/2.21.7, lz4-java1.11.4,
snappy-java1.1.10.9. Native compression round trips use a real compatibility broker.
Historical2026-11-07 scans: all five rebuilt application images0 critical/0 high;
patched Kafka0 critical/0 high/1 medium; provider26.8.2 had0 critical/4 high.
The provider HIGH findings included OpenJDK, SQLite and Guava; no blanket suppression
was added. Current CI re-evaluates actual runtime images against the available DB.
Residual findings require production remediation/review, not a readiness claim.

Production-only guards reject unsafe TLS/CORS/DB options and schema mutation.
Separate MigrateDatabase entry points start no HTTP server, consumer or publisher.
Production Compose requires external secrets/certificates, runtime/migrator role
separation, private networking and immutable images. Its TLS fixture actually tested
TLS1.2/1.3, CA rejection and Host rejection. No production business stack was started.

## Executed verification

- Complete patched Maven verify on2026-11-07: User23, Ledger115, Notification27,
  Analytics34, Gateway18, DBA4, monolith248 with one opt-in live skip; no failures.
  Final CI verifies the publication revision again.
-2026-11-17: protected image builds, private startup and actual Keycloak client
  validation passed; full API/browser PKCE, provisioning, transfer, notification,
  analytics, replay/backfill/reconciliation, broker outage/recovery and logout passed.
  Chromium6.3s / runner9.0s. Temporary provider identities removed; audit retained.
- Actual contracts validated1000 current committed events:500 identity,196 transfer,
  54 account and250 posting payloads, without logging their contents.
- Real producer-denial probes and unauthenticated broker metadata denial passed.
  Broker recreation/upgrade tests preserved36 business partition offsets and18
  numeric committed group positions; uncommitted '-' assignments are not counted.
- Seven actual encryption/retention/provider-selection unit tests passed on2026-11-17.
- Authenticated backup of all five current databases:36.503s. Restricted isolated
  restore verification:19.774s; every table/sequence fingerprint matched. Restored
  Ledger SQL invariants passed; no source was overwritten. Restored rows: Analytics3466,
  identity1691, Ledger3208, Notification2054, User1049. All recovery DBs/volumes retained.
- Earlier Phase F physical pg_verifybackup/named-point WAL recovery passed; keep/exclude
  markers proved point recovery. This proves a lab restore, not production database HA.
- Phase G verified actual metrics,19 dashboard panels, privacy canaries, linked logs
  and distributed money traces. Optional services are scheduled around the4GB VM.

## Performance and failure evidence

Phase H artifacts are committed under tests/performance/results. Shared8GB Apple
Silicon host /4GB Docker VM,1–8 VUs, bounded20–35s cases and300s soak. Observed
stress workload38.57RPS/p95=371.15ms; not a measured capacity ceiling. Soak executed
two actual token refreshes. Duplicate replay, balances, postings and Kafka manifests
were reconciled after load. The pool-starvation failure was fixed using10 Ledger
connections per instance within the restricted role budget30; failed runs remain
explicitly failed evidence. Benchmarks precede final security upgrades.

Controlled replica/cache/broker/database/connection/network faults executed on
synthetic data. Gateway kill:50/50 reads200. Ledger kill:8x201,29x200,3x500 during
40 requests/8 keys; all8 original keys settled once, conserving0.0008. Redis outage
preserved SQL reads and rejected writes503 before forwarding. Kafka outage retained
two outbox records, then delivered one notification. Database restart had transient
500/503 responses but original keys settled once. Role budget rejected9 probe sessions;
network50ms egress delay changed median52.21→366.88→53.38ms after removal.

## CI/CD and publication

CI covers compile/unit/integration/Flyway, source secrets, image vulnerability scans,
Docker builds, Compose, isolated TLS, real event contracts and full-stack Keycloak/
Chromium smoke tests. Production deployment is manually gated. New service image
publication is explicit opt-in and has not been invoked. Existing monolith GHCR
publication on source pushes is retained; no public server deployment was run.
Documentation-only receipts use dedicated link/secret checks and do not rebuild or
publish unchanged runtime images.

| Published phase | Backend SHA | Successful CI run |
|---|---|---|
| A | 0a8781de91f05f935d89d66cb65e1da2aa132c4b |42218237669 |
| B | 82997fde4280b8de3b649d6276808eb13e3dd532 |42221287503 |
| C | 6c1325fb6a38f4fda04a1fd6785a0d7a02e048a7 |42224430846 |
| D | 25a28ab78f24d481ec993b0161a7214a2858c619 |42227334980 |
| E | ece425a893a16c91bacc25c479bd984f5b51ff20 |42239735085 |
| F | c53eb8df912d7e3d36b51d67e3d46e3482909ec5 |42308685427 |
| G | 5054eb2e4e22f63bf057e1558662c201fcb7e3e5 |42352264110 |
| H |14b23f0e1d83c97a772d3153a0e194aaf7dea77f |42355856981 |

Frontend a70afab85289b299fe49f481fc909b999d25faf6 is independently published and
pinned; its prior CI42239146949 passed. Phase I source SHA/CI/main confirmation
will be appended after actual publication. A documentation receipt necessarily
follows its verified code commit; the final remote ref is also reported to the user.

## Run and rollback

README.md contains fresh local init/build/start commands. Existing data uses original
private envs and verified identity selection. Use stop, never down-v. For incidents
see docs/runbooks/platform-operations.md. Fence writes and take authenticated backups
before real migration. Reconcile IDs, sequences, postings, balances, idempotency and
outbox before selecting one authoritative writer. No automatic real migration exists.

For local broker rollback, the actual3.9 clone rollback was verified while metadata
stayed3.9-IV0. It is an emergency fenced compatibility procedure, not approval to
redeploy an old vulnerable image publicly. Identity rollback requires stopping the
new provider and explicitly selecting the retained original database/version; new
identity writes must be reconciled first. Do not downgrade an upgraded database
schema or restore over an active financial database.

## Remaining production gates

External secret/certificate custody, vulnerability remediation and penetration review,
approved real identity/data migration, independent off-host backup/restore drills,
multi-host broker/database failover, long-duration load/SLO work and deployment-specific
operational/regulatory approval remain. JWT revocation and lifecycle projections are
not instantaneous during outages. No cloud throughput or multi-host HA is claimed.
No production/public traffic, real financial records, db-lab or original untracked
architecture documents were modified by this rollout.
