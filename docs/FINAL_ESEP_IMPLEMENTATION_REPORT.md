# ESEP implementation and verification report

The local implementation passed all12 CI jobs, was published to main and passed
the complete main pipeline. Its server-deployment job was deliberately skipped.
This report does not approve real customer operation or production traffic.

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

The refreshed local scan on2026-11-17 found0 critical/0 high in Gateway and
patched Kafka, but0 critical/6 high in Keycloak26.8.2 (43 medium). The increase
from the historical four is new SQLite advisories, not a scan suppression.

| Remaining provider HIGH | Package | Scanner-listed fix |
|---|---|---|
| CVE-2026-91403 | java-21-openjdk-headless21.0.13.0.9-1.2.el9 | Not listed |
| CVE-2026-119373 /119374 /119379 /119586 | sqlite-libs3.34.1-11.el9_8 | Not listed |
| CVE-2026-102554 | guava33.6.0-jre |33.7.2 |

Production remains gated on an upstream/provider image update and reviewed
remediation; no unsupported provider JAR replacement or severity waiver was used.
The pinned Nginx image also has three HIGH libxslt findings:
CVE-2026-125350, CVE-2026-87636 and CVE-2026-9947, fixed in1.1.43-r4.
These remain explicit production remediation work, including the pinned frontend
runtime image; the critical-only CI policy does not assert absence of HIGH findings.
The local Redis image reports0 critical/0 high/1 medium, but its Alpine3.21 base
is end-of-support according to the scanner. Absence of advisories is insufficient
for production approval; provision a supported external Redis/TLS deployment.

CI44118726609 correctly stopped on the original PostgreSQL image's critical
Go-runtime CVE-2025-68121 in gosu, plus44 HIGH findings. The derivative keeps
PostgreSQL17.11 and rebuilds upstream gosu1.20 with pinned Go1.27.4 and
x/sys0.44.0; krb5-libs1.22.3-r0/libxslt1.1.43-r4 close OS advisories. Its
actual refreshed scan reports0 findings at every severity, including UNKNOWN.
Both local database containers use this image and run PostgreSQL as UID70.
After a new authenticated five-database backup, all rows/sequences matched exactly
after replacement. ICU78.1-r0 and musl1.2.6-r3 match both previous containers.
No PostgreSQL engine upgrade, identity schema downgrade or financial migration ran.

Production-only guards reject unsafe TLS/CORS/DB options and schema mutation.
Separate MigrateDatabase entry points start no HTTP server, consumer or publisher.
Production Compose requires external secrets/certificates, runtime/migrator role
separation, private networking and immutable images. Its TLS fixture actually tested
TLS1.2/1.3, CA rejection and Host rejection. No production business stack was started.

## Executed verification

- Complete patched Maven verify on2026-11-07: User23, Ledger115, Notification27,
  Analytics34, Gateway18, DBA4, monolith248 with one opt-in live skip; no failures.
  Final12-job CI44126587328 repeated verification successfully on2026-11-17.
-2026-11-17: protected image builds, private startup and actual Keycloak client
  validation passed; full API/browser PKCE, provisioning, transfer, notification,
  analytics, replay/backfill/reconciliation, broker outage/recovery and logout passed.
  Chromium6.3s / runner9.0s. Temporary provider identities removed; audit retained.
  After the PostgreSQL helper/library fixes and retainer isolation, the complete
  API/browser/broker-recovery flow passed again: Chromium7.8s / runner9.5s.
- Actual contracts validated1000 current committed events:500 identity,196 transfer,
  54 account and250 posting payloads, without logging their contents.
- Real producer-denial probes and unauthenticated broker metadata denial passed.
  Broker recreation/upgrade tests preserved36 business partition offsets and18
  numeric committed group positions; uncommitted '-' assignments are not counted.
  Actual container recreation was repeated successfully on2026-11-17. The
  authenticated read-only Kafka exporter then collected36 partition-offset,
  18 consumer-offset and18 lag series from the upgraded broker.
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

Initial Phase I CI44118161009 failed because CAP_DAC_OVERRIDE removal exposed
Linux bind-mount permissions in the isolated scanner. The scanner now mounts
individual public-artifact files from a private directory, without workspace or
Docker-socket access. Its actual local scan passed; the complete CI was restarted.
CI44122457296 then exposed inherited Compose labels on never-started image
retainers. Those labels are cleared before creation so retained images cannot
masquerade as business replicas. Real service selection/ACL/E2E checks remain
mandatory; no failing security gate was disabled.
CI44124151432 subsequently failed an administrative Kafka command before the
financial E2E. No diagnostic cause was exposed by its protected subprocess helper,
so the exact cause is unproven. Administrative checks now report only allowlisted
exception classes and broker termination/OOM state, allow15-second metadata/group
deadlines and96MiB CLI heap. The broker cgroup permits768MiB for its JVM plus
health/admin JVM bursts; it does not reserve that memory continuously. Actual local
ACL checks and broker recreation passed again with unchanged offsets/groups.

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
| I |ffbdc6ae53cc483bcf4ceef55bc3e9885567c771 |44126587328: all12 jobs success |

Frontend a70afab85289b299fe49f481fc909b999d25faf6 is independently published and
pinned; its prior CI42239146949 passed. Phase I source was fast-forward integrated
and pushed to origin/main; git ls-remote confirmed
ffbdc6ae53cc483bcf4ceef55bc3e9885567c771 on2026-11-17. No force push, public
deployment or financial data migration ran. The separate
[main workflow44128575834](https://github.com/Bakhyzh/esep-api/actions/runs/44128575834)
completed successfully: all12 verification jobs and the retained monolith GHCR
image job passed; the production server deployment was skipped. The
[feature CI44126587328](https://github.com/Bakhyzh/esep-api/actions/runs/44126587328)
also passed all12 jobs on exactly the same source SHA. Documentation workflow
44128575649 passed. Frontend origin/main was rechecked and remains the pinned
a70afab85289b299fe49f481fc909b999d25faf6.

Phase I logical source history:

- 9a9fb384da9f29dadecf94c28fd33c8ade7fcff8: local hardening and integrated verification.
- 90c2a552d87a11d1f9b750111a10fafed052042a: isolated scanner file access on Linux.
- 81706569286ad33391a90fb851f949453cfe0dfa: PostgreSQL helper/library security fixes.
- ed55088a0386fec51c86b889a8abfd6d21863a3d: retained-image Compose isolation.
- ffbdc6ae53cc483bcf4ceef55bc3e9885567c771: bounded Kafka administration/diagnostics.

A documentation receipt necessarily follows the verified code commit. This file
records that immutable implementation/main verification point; resolve the later
documentation commit with git log -1 --format=%H -- docs/FINAL_ESEP_IMPLEMENTATION_REPORT.md.
The final current remote ref is also reported to the user after the receipt push.

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
