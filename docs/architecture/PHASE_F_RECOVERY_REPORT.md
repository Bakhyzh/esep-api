# Phase F — PostgreSQL operations and recovery

Status: role/crypto/backup/restore/PITR and actual application regression verified;
publication and remote CI are tracked separately. Production is unchanged.
Work began2026-11-06 and continued2026-11-07 on M1/8GB, Docker4GB.

Implemented private operations tools at infrastructure/ops, independent real-DB
permission/migration tests at infrastructure/ops-tests, operational roles, runtime
SQL/lock/idle deadlines and connection budgets, safe pg_stat_statements/activity/
locks/vacuum/WAL diagnostics, age streaming backup/retention, isolated restricted
restore, and a physical named-point recovery lab. Detailed startup, failure behavior
and all incident procedures are in docs/runbooks/platform-operations.md.

No existing V1–V8 migrations or financial business logic were changed. User runtime
can no longer mutate Flyway history; Ledger projector can no longer consume financial
sequences. Each migration owner still owns its schema; application/projector/monitor/
backup/restore privileges are separate. Monitoring schema keeps fresh public schemas
empty before Flyway V1. pg_stat_statements is read through pg_monitor without granting
business row access. SQL bind/error logging is restricted; password-bearing role
scripts suppress statement/error SQL logging in their session.

## Executed evidence

- Independent operations Maven verify:4 real PostgreSQL17.11 Testcontainers tests
  passed. Actual entrypoint scripts provision a fresh cluster; all four services'
  Flyway histories migrate and validate with non-superuser migration roles. Tests
  prove denied runtime DDL/history/cross-DB writes, projector sequence restrictions,
  read-only backup/monitor separation, isolated restore connection refusal,
  operational connection budget exhaustion, and a real5s SQL timeout followed by
  rollback with no partially inserted profile.
- Four real age/filesystem tests passed (0.548s sample): minimum/expired retention,
  incomplete artifacts, symlink/external-file safety, modified ciphertext/checksum/
  authentication failure, and missing original identity refusal. Fixtures are
  disposable bytes; these are not substituted for database restore testing.
- Actual consistent encrypted business backup:4 databases,20.032s, complete-stream
  authentication passed. Full restricted restore:7.529s, table/sequence fingerprints
  and financial SQL matched; source untouched.
- Provider-inclusive backup:5 databases including existing Keycloak,53.608s. Existing
  Keycloak was resumed healthy using the same container and preserved volume.
  Restricted restore:28.953s. Matched Analytics10 tables/576 rows/1 sequence;
  Ledger10/532/5; Notification7/397/2; User4/294/3; Keycloak100/1643/0.
  Row fingerprints include all public data, numerical IDs, identities, historical
  attribution, balances, postings, idempotency and outboxes; provider hashes/credentials
  were copied only inside encrypted backups and protected verification databases.
- Initial restore found the empty target's existing public schema. The tool was
  corrected to replace only that just-created empty schema (no CASCADE); complete
  four/five-database restores were rerun. Failed verification DBs were retained;
  existing source schemas/data were never dropped or rewritten.
- Physical PITR lab: pg_basebackup SHA256 manifest and pg_verifybackup passed;
  target esep_keep at LSN0/4000090 retained the keep marker and excluded the later
  committed marker. Recovered server promoted successfully,4.608s local run.
  Three new labeled volumes retained; no financial DB was accessed. This is a tiny
  mechanism test, not a financial production RTO/RPO or HA/failover claim.
- Read-only diagnostic output and retention dry-run executed. Both complete real
  artifacts retained; no real backup expiration was needed. PostgreSQL reported
  parameter log limits0 and terse error verbosity after configuration restart.

Backup data never passes through a plaintext dump file, shell password argument,
log or Git. Private key0600 and artifact directories0700 are ignored. The original
backup identity is required; the restore tool will not replace a missing key.
Production requires offline key custody and independent backup storage, reviewed
WAL retention/recovery design and tested Kafka-offset/projection reconciliation.

No replicated database failover was implemented or claimed. Main development
PostgreSQL remains a single authority; PITR uses separate synthetic lab volumes.
All database verification copies/lab volumes remain for inspection. Existing
financial/Keycloak volumes, unrelated Java8080 process, db-lab and original
untracked architecture documents were preserved.

Publication context: prior phase E backend main ece425a893a16c91bacc25c479bd984f5b51ff20,
CI42239735085 all7 jobs success; frontend main a70afab85289b299fe49f481fc909b999d25faf6,
CI42239146949 success. After rebuilding User/Ledger with restricted-grant callbacks, the full12-container
platform started healthy. Actual Keycloak → User → Kafka → Ledger → Notification /
Analytics and real Chromium first-login/account/transfer/report/logout passed
(10.3s test /12.6s runner), followed by successful financial invariant SQL.
Safe diagnostics also executed with active application pools. Phase F publication/
remote CI are pending at report creation.
