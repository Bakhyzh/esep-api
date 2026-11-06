-- Synthetic local/test bootstrap only. Real migration supplies its original system ID.
INSERT INTO ledger_principals(id,kind) VALUES (9223372036854775807,'SYSTEM') ON CONFLICT DO NOTHING;
INSERT INTO accounts(user_id,currency,type)
SELECT 9223372036854775807,c,'SYSTEM' FROM unnest(ARRAY['KZT','USD','EUR']) c
WHERE NOT EXISTS (SELECT 1 FROM accounts WHERE type='SYSTEM' AND currency=c);
