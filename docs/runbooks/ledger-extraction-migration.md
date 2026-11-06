# Ledger extraction migration and single-writer rollback

Phase 3.1 runs **isolated synthetic data only**. No actual monolith-data migration
or public routing switch is authorized by the local implementation. Ledger has a
service-specific Flyway V1 baseline; monolith V1–V8 remain unchanged.

## Migration plan for later explicit approval

1. Inventory source schema/Flyway checksums, users/account/transaction/entry/outbox
   IDs, supported currencies/system owner ID, approved issuer/sub mappings,
   created_by/key/hash scope, pending/published outbox and Kafka offsets. Document
   every consumer and contract version. Resolve conflicting owner mappings first.
2. Rehearse a pg_dump custom-format backup and restore into a new isolated source
   clone. Protect backup/roles/manifest and keys, record checksums and restore
   evidence. A dump file without a successful restore is not sufficient.
3. Fence old financial HTTP writes, demo/background writers and outbox publisher;
   drain in-flight transactions. Gate is operationally enforced: stop the old
   writer and revoke its financial DML credential during the switch. Record fence
   evidence. Only one authoritative writer may write a given set of accounts.
4. Create a NEW target ledger_db with the independent baseline (without local/test
   seed locations). Never restore monolith schema/history directly into it. Do not
   run Flyway baseline-on-migrate/repair/clean against financial data.
5. Export approved minimal principals: original numeric users.id and HUMAN/SYSTEM
   classification only. Export exact verified issuer/sub/linked_by/time separately.
   No email, password_hash, user preferences or default ADMIN profile copy.
6. Under one target import transaction, copy only accounts, transactions,
   ledger_entries and outbox_events with explicit column lists and original IDs,
   versions, amounts/currencies, timestamps, statuses, request_hash/created_by/key,
   event UUID/key/attempt/published state. Preserve FK references to the new local
   principal IDs. Preserve the source system principal/account IDs, not the local
   synthetic reserved ID. Existing target data is a conflict, never an overwrite.
7. Validate existing outbox payloads against the consumer-approved version contract.
   The old stream has no schemaVersion; converting pending records needs an audited
   deterministic additive envelope transform retaining eventId and business IDs.
   Coordinate old/new topics and consumer checkpoints so no existing operation is
   sent as a new operation or consumed twice without deduplication. Phase 3.1's
   isolated versioned topic must not be silently attached to legacy notifications.
8. Copy identity bindings only from independently approved exact issuer/sub records;
   reject conflicting or system bindings. Map all historical created_by references,
   including retired users, without reusing IDs. Principals retain historical IDs
   even when an identity is disabled. User/identity authority remains outside Ledger.
9. Set generated account/transaction/entry/outbox/binding sequence state above all
   imported IDs (including deleted/gap reservations); principal IDs are externally
   assigned and have no local allocator. Capture sequence manifests and next values.
10. Compare source/target row counts and canonical per-table/chunk checksums, per-
    account balance vs signed postings, exactly two balanced same-currency postings
    per completed operation, user nonnegative balances, zero currency totals,
    FK orphans=0, unique active accounts and all idempotency keys/fingerprints.
    Run scripts/reconcile.sql and Flyway validate. Any mismatch blocks cutover.
11. On a clone, replay approved old idempotency requests and require original IDs,
    perform own/foreign account and ADMIN-not-owner security checks, concurrency
    and failure tests. No production test debit/top-up without separate approval.
12. Record the release/DB/identity/backup/topic/offset manifest. Start one Ledger
    authority, switch approved routes only after financial/API checks, then resume
    the coordinated publisher/consumers. Preserve the old DB read-only and old
    binaries/config throughout the rollback window. Reconcile immediately and monitor.

The selective export/import and source-topic conversion above are a reviewed plan,
not an implemented bulk migration tool or a completed production rehearsal. The
current scripts deliberately cannot connect to or overwrite the monolith DB.

## Rollback

- Current synthetic extraction: stop the isolated Ledger API/project, preserve its
  volumes and restore-check DBs. The monolith still owns its own accounts/routes;
  no routing/database rollback is necessary and no financial data is deleted.
- Approved real cutover **before any new Ledger write**: fence Ledger, verify no
  writes/outbox publication occurred, restore the previous route/writer/credentials
  from the recorded manifest, retain the new DB for investigation.
- **After any new Ledger write**, the old snapshot is stale. Do not merely turn the
  monolith back on. Freeze/drain, export all new IDs/principals/transactions/entries/
  request hashes/outbox state and reconcile/apply a separately tested reverse
  migration, or forward-fix the last compatible Ledger build. Record Kafka effects
  already delivered and dedup checkpoints. Never reset balances/sequences or restore
  an old snapshot to hide committed money movements.

Production routing remains blocked until reverse-data recovery or a validated
forward-fix strategy, restored backups and consumer/topic migration are approved.

## Executed local evidence

The synthetic restore smoke creates a new uniquely named DB, streams pg_dump into
it without overwriting anything, compares all Ledger tables/Flyway history and
sequence next values, and checks zero financial reconciliation violations. Test
transactions demonstrate rollback after both postings and outbox were written.
The live API smoke stops/restarts the isolated Kafka and confirms committed money
survives with a durable pending event. These checks do not authorize real-data cutover.
