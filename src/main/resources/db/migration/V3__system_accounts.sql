-- System (funding) accounts are the "outside world" side of deposits and withdrawals.
-- A deposit of 100 to Alice = DEBIT 100 on the system account, CREDIT 100 on Alice's account,
-- so every transaction still sums to zero. System accounts may go negative:
-- their negative balance = total money that entered the system.
ALTER TABLE accounts
    ADD COLUMN type VARCHAR(10) NOT NULL DEFAULT 'USER',
    ADD CONSTRAINT ck_accounts_type CHECK (type IN ('USER', 'SYSTEM'));

ALTER TABLE accounts DROP CONSTRAINT ck_accounts_balance_non_negative;
ALTER TABLE accounts
    ADD CONSTRAINT ck_accounts_balance_non_negative CHECK (type = 'SYSTEM' OR balance >= 0);

-- Technical owner of system accounts. '!' is not a valid password hash, so nobody can log in as it.
INSERT INTO users (email, password_hash, role)
VALUES ('system@esep.internal', '!', 'USER');

INSERT INTO accounts (user_id, currency, type)
SELECT u.id, c.code, 'SYSTEM'
FROM users u
         CROSS JOIN unnest(ARRAY ['KZT', 'USD', 'EUR']) AS c(code)
WHERE u.email = 'system@esep.internal';
