-- SHA-256 of the request parameters (type, accounts, amount).
-- Same Idempotency-Key + same hash      -> replay the original result.
-- Same Idempotency-Key + different hash -> client bug, reject with 422.
ALTER TABLE transactions ADD COLUMN request_hash VARCHAR(64);

-- rows created before this migration have no hash; they can never be replayed
UPDATE transactions SET request_hash = 'legacy' WHERE request_hash IS NULL;

ALTER TABLE transactions ALTER COLUMN request_hash SET NOT NULL;
