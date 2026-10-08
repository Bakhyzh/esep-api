-- Synthetic load for EXPLAIN experiments. NEVER run against a real database.
-- 10 000 users with one KZT account each, 1 000 000 transfers (2 000 000 ledger entries) over 365 days.
INSERT INTO users (email, password_hash, role)
SELECT 'perf' || g || '@esep.test', 'x', 'USER'
FROM generate_series(1, 10000) AS g;

INSERT INTO accounts (user_id, currency, balance)
SELECT id, 'KZT', 0
FROM users
WHERE email LIKE 'perf%';

CREATE TEMP TABLE perf_accounts AS
SELECT row_number() OVER (ORDER BY id) AS n, id FROM accounts WHERE type = 'USER';

CREATE TEMP TABLE perf_transfers AS
SELECT g                                                        AS n,
       1 + floor(random() * 10000)::int                         AS from_n,
       1 + floor(random() * 10000)::int                         AS to_n,
       round((1 + random() * 50000)::numeric, 2)                AS amount,
       now() - random() * INTERVAL '365 days'                   AS created_at
FROM generate_series(1, 1000000) AS g;

INSERT INTO transactions (id, type, status, idempotency_key, request_hash, created_by, created_at)
OVERRIDING SYSTEM VALUE
SELECT t.n, 'TRANSFER', 'COMPLETED', 'perf-' || t.n, 'perf', a.user_id, t.created_at
FROM perf_transfers t
JOIN perf_accounts p ON p.n = t.from_n
JOIN accounts a ON a.id = p.id;

INSERT INTO ledger_entries (transaction_id, account_id, amount, direction, created_at)
SELECT t.n, f.id, t.amount, 'DEBIT', t.created_at
FROM perf_transfers t JOIN perf_accounts f ON f.n = t.from_n
UNION ALL
SELECT t.n, r.id, t.amount, 'CREDIT', t.created_at
FROM perf_transfers t JOIN perf_accounts r ON r.n = t.to_n;

SELECT setval(pg_get_serial_sequence('transactions', 'id'), (SELECT max(id) FROM transactions));

-- fresh statistics for the planner + visibility map for Index Only Scans
VACUUM ANALYZE;
