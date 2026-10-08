-- Accounts are never deleted (ledger history must stay consistent), they are closed.
ALTER TABLE accounts
    ADD COLUMN status    VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN closed_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_accounts_status CHECK (status IN ('ACTIVE', 'CLOSED'));

-- One active account per user per currency. Closed accounts are not counted (partial index).
CREATE UNIQUE INDEX ux_accounts_user_currency_active
    ON accounts (user_id, currency)
    WHERE status = 'ACTIVE';
