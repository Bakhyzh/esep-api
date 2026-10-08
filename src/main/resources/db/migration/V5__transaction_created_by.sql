-- Who initiated the transaction. Idempotency keys become unique PER USER:
-- two clients that happen to generate the same key must not see each other's results.
ALTER TABLE transactions ADD COLUMN created_by BIGINT REFERENCES users (id);

-- rows from before authentication existed are attributed to the technical system user
UPDATE transactions
SET created_by = (SELECT id FROM users WHERE email = 'system@esep.internal')
WHERE created_by IS NULL;

ALTER TABLE transactions ALTER COLUMN created_by SET NOT NULL;

ALTER TABLE transactions DROP CONSTRAINT uq_transactions_idempotency_key;

-- also serves as the index for the created_by foreign key (it is the leading column)
ALTER TABLE transactions
    ADD CONSTRAINT uq_transactions_created_by_idempotency_key UNIQUE (created_by, idempotency_key);
