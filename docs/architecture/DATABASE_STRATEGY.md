# Database ownership, integrity and recovery

Ledger keeps accounts, balances, transaction state, two postings and outbox records
inside one PostgreSQL ACID transaction. Sorted pessimistic account locking avoids
opposite-direction lock order inversions. Unique creator/idempotency constraints
handle concurrent duplicate requests. Amounts use BigDecimal / NUMERIC(19,4).
No floating-point representation is authoritative money.

The system account may carry the opposite side of an authorized top-up; human
accounts cannot overdraw. Completed transactions must have exactly two opposite
postings and all balances must agree with postings. The read-only verification is
in tests/financial/ledger-invariants.sql. Multiple Ledger instances use the same
financial database and locking/idempotency constraints.

Business runtime, Flyway migrator, identity projector, backup, monitoring and restore
roles have distinct privileges. Production runtimes disable Flyway and validate
schema. MigrateDatabase is a separate entry point with fixed service locations,
verified TLS, explicit credentials, bounded SQL/lock waits and clean disabled.

Independent baselines are owned by each service; monolith V1–V8 are unchanged.
Never point the old monolith and extracted service at the same authoritative accounts.
Actual migration requires a verified backup, write fence, copied original IDs,
sequence verification, posting/balance/idempotency reconciliation, one writer switch
and a rehearsed recovery. No real financial migration was executed automatically.

Ops tooling encrypts dumps and manifests with age, verifies row/sequence hashes in
new restore databases, and performs an isolated physical backup/PITR exercise.
Never remove volumes to recover a failed migration. The local identity upgrade uses
a new restored database; the original keycloak_db remains a rollback source.
Backups must follow provider_database(), not an obsolete inactive identity DB.
See docs/runbooks/platform-operations.md and PHASE_F_RECOVERY_REPORT.md.
