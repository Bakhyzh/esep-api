-- Analytics reads only DEBIT entries of a user's accounts within a time range.
-- Partial index (WHERE direction = 'DEBIT'): about half the size of a full one.
-- INCLUDE (amount, transaction_id): the index has every column the reports need,
-- so PostgreSQL can answer with an Index Only Scan, without visiting the table.
-- CONCURRENTLY: builds without locking writes to ledger_entries (Flyway runs it outside a transaction).
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_ledger_entries_debit_account_created
    ON ledger_entries (account_id, created_at) INCLUDE (amount, transaction_id)
    WHERE direction = 'DEBIT';
