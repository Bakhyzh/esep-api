# Local platform operations and recovery

Scope: the isolated esep-platform-local project and its dedicated databases.
Production/VPS operations are not authorized by these commands. Existing db-lab,
original financial volumes and other local projects are never cleanup targets.
Do not remove volumes, overwrite a source DB, or automatically switch writers.

## Roles and diagnostics

Business schemas belong to distinct migration roles. Runtime roles own no DDL or
migration history. Ledger identity projector can mutate only its projection and
binding sequence, not financial accounts or their sequences. platform_ops has
pg_monitor without access to business rows; platform_backup has read-only data
access; platform_restore cannot connect to the four authority databases and owns
only newly created verification databases. All passwords are external private
`.env.platform` values. Never paste them or tokens into commands/history/logs.

Runtime/projector SQL deadlines are5s, lock wait2s, idle transaction30s. These are
local starting settings, not a production sizing claim. Hikari pools remain
bounded; Ledger's role allows30 connections for two10-connection pools. Monitor
active/waiting connections before changing budgets; max_connections is80.

```sh
python3 infrastructure/ops/diagnostics.py --database ledger_db
```

This read-only monitor uses activity, blocking PIDs, locks, dead tuples/autovacuum,
query IDs/timing, WAL/archive and settings. It deliberately excludes query text,
bind parameters, credentials and financial rows. pg_stat_statements lives in a
separate monitoring schema, so a fresh public schema can receive Flyway V1 without
an unsafe baseline. Slow statements over500ms are logged; bind logging is disabled
and error verbosity is terse. Password-bearing role scripts suppress session SQL
logging. Do not enable JDBC/HTTP wire logs to debug a financial request.

For an existing synthetic volume after adding private OPS/BACKUP/RESTORE password
variables, recreate PostgreSQL with the same volume and explicitly apply:

```sh
docker compose -f compose.platform.yaml --env-file .env.platform up -d --no-build --wait postgres
docker compose -f compose.platform.yaml --env-file .env.platform exec -T postgres \
  psql -X -U platform_bootstrap -d postgres -v ON_ERROR_STOP=1 \
  -f /docker-entrypoint-initdb.d/20-ops.sql
```

These idempotent scripts create missing roles and monitoring objects, not new
identities or money. They do not rotate an existing role's password. Migration
callbacks apply explicit table/sequence grants; changing an environment password
without an approved DB rotation will correctly fail authentication.

## Encrypted backup and isolated restore

Install maintained `age` (Mac: `brew install age`; CI uses its OS package). The
first backup creates an X25519 identity in ignored `.platform-secrets/backup.agekey`
(mode0600) and private `.platform-backups` directories (0700). Copy the identity to
approved offline secret custody: losing it makes old backups unrecoverable. Keep
backup storage separate from source disks in a real deployment. This local lab
proves restore mechanics, not resilience against loss of the Mac itself.

1. Quiesce **all** isolated business writers and consumers. The tool refuses a
   running User/Ledger/Analytics/Notification process, a remote Docker context, an
   unrelated PostgreSQL volume or a source outside the known project. Multiple DB
   snapshots are consistent only under this write/consumer pause, not a distributed
   transaction. Fence/stop approved sources before a real recovery operation.
2. For a provider-inclusive backup, stop the existing local Keycloak application
   only, retaining its database. Reuse its exact container; do not reimport/reset a
   realm. Resume it in a finally/recovery step. The audited provider project is
   esep-identity-dev. Never take this availability pause on production automatically.
3. Execute:

```sh
python3 infrastructure/ops/backup.py
# Optional quiescent provider, with its existing private credential directory:
python3 infrastructure/ops/backup.py --include-keycloak --identity-dir ../esep-api/infrastructure/keycloak
python3 infrastructure/ops/restore-verify.py .platform-backups/<reported-artifact>
```

Dumps stream directly from pg_dump into age; no plaintext dump/temp file or password
argument is written. The encrypted manifest records table SHA256 fingerprints and
row counts, exact sequence values, source revision and ciphertext hashes. Every
file is authenticated before restore begins. Restore creates fresh random
restore_verify_* databases and uses a restricted non-superuser, one transaction
per database. It never accepts an existing target name or overwrites a source.
Only the **new empty** verification DB's default public schema is replaced, without
CASCADE, because pg_dump recreates that schema. Monitoring extensions/role passwords
are recreated through separate versioned ops configuration, not copied as globals.

Verification compares every supported public table and sequence, not only balances;
Ledger also runs its double-entry/conservation/ownership-reference invariants.
Verification copies and PITR volumes are deliberately retained for inspection and
are never connected to an application writer. Production approval is required
before any real traffic/configuration switch. Kafka group offsets/replay and source
outbox reconciliation must be planned alongside recovery: restoring a projection
DB alone while retaining newer offsets can skip events. Authoritative outboxes
retain events; Analytics has the explicit fenced backfill/reconciliation API.
This phase does not claim complete Kafka disaster recovery beyond the already
executed outage/outbox recovery tests.

Retention is dry-run by default:

```sh
python3 infrastructure/ops/backup-retention.py --keep 3 --days 7
# Explicit operation on this tool's authenticated owned artifacts only:
python3 infrastructure/ops/backup-retention.py --keep 3 --days 7 --apply
```

At least two complete backups and one day must be retained. Expiration authenticates
all candidates, rejects symlinks/unexpected files and does no recursive deletion.
Incomplete artifacts are never expiration candidates. No database/volume deletion.
Four real age/filesystem tests cover expired-only deletion, minimum retention,
incomplete/symlink protection, modified ciphertext and missing-key refusal.

## Physical PITR lab

```sh
python3 infrastructure/ops/pitr-lab.py
```

The lab creates uniquely named, labeled **new** primary/archive/recovery volumes,
uses a pinned PostgreSQL17.11 image without network access/published ports, takes
pg_basebackup with SHA256 manifest, runs pg_verifybackup, records a named restore
point, writes a later excluded marker, archives WAL, and recovers a separate copy
to the named point. It checks the keep/exclude markers and promotion. It never
mounts or queries a financial database. All lab containers are stopped and volumes
retained. This is recovery validation, not replication/failover/high availability.
The main platform still needs a separately approved production WAL/archive design.

## Incident procedures

| Incident | Diagnose and contain | Recovery and validation |
|---|---|---|
| Database unavailable | Check service readiness, PostgreSQL health and safe connection diagnostics; retain idempotency keys; never use the monolith's other dataset as fallback | Restore connectivity/restart only the isolated intended container; retry uncertain operations with original keys; run financial invariant SQL and outbox checks |
| Pool exhausted | Compare Hikari active/pending metrics (monitoring profile), pg_stat_activity by role and transaction age; stop synthetic load before raising limits | Find leaks/blocking transactions, preserve reserved operational connections; do not blindly increase max_connections; validate acquisition recovery and idempotency |
| Deadlock/long blocking | Inspect blocking PID graph and lock modes without dumping SQL/values; fence writes if an operator-approved intervention is needed | Correct lock order/query, cancel only a verified application backend through an authorized DBA session; never kill unknown host processes; check rollback/invariants |
| Slow SQL | Inspect query IDs/timing, dead tuples, last analyze/vacuum and plan on synthetic data | EXPLAIN ANALYZE only approved read queries; review indexes/statistics and workload; avoid an unapproved live rewrite/lock-heavy migration |
| Disk full/WAL growth | Inspect database sizes, pg_stat_wal/archiver and host/container storage; stop new writes before losing durability | Expand approved storage or expire verified backups by policy; never delete pg_wal or a financial volume; validate fsync/health, backups and invariants |
| Kafka outage | Existing identity/financial writes can commit with retained outboxes; new identity projections can lag; inspect backlog/consumer lag/DLT | Restore broker/auth/connectivity, retain stable event IDs and group identity; await catch-up, dedup and source/projection reconciliation; never claim exactly-once delivery |
| Redis outage | Analytics falls back to SQL; Gateway rejects mutations503 before forwarding while reads may continue | Restore Redis; durable analytics generations prevent old cache reuse; retry original financial keys; do not use Redis as a balance authority |
| Failed migration | App must stay unready; preserve source backup and exact checksum/error category | Fix a new forward migration or use the verified image against its compatible schema; do not edit V1–V8, repair checksums blindly or auto-baseline a non-empty authority DB |
| Restore from backup | Quiesce/fence intended writers and consumers, authenticate complete backup/key, restore into new isolated names | Compare all rows/sequences and financial invariants; reconcile identities/outboxes/projections and Kafka offsets; obtain required real-data/traffic-switch approval before one-writer cutover |

After any money-affecting incident, run `tests/financial/ledger-invariants.sql` against
the intended authority using an approved operator role. It is read-only and reports
only pass/fail. No automated runbook performs production failover or destructive
financial repair. Monitoring and measured fault/load tests are subsequent phases.
