-- Read-only acceptance gates; every row must have violations=0.
SELECT 'account_balance_vs_postings' AS invariant,count(*) AS violations
FROM accounts a WHERE balance<>(SELECT coalesce(sum(CASE direction WHEN 'DEBIT' THEN -amount ELSE amount END),0)
  FROM ledger_entries WHERE account_id=a.id)
UNION ALL
SELECT 'negative_human_balance',count(*) FROM accounts WHERE type='USER' AND balance<0
UNION ALL
SELECT 'unbalanced_or_incomplete_transaction',count(*) FROM (
 SELECT t.id FROM transactions t LEFT JOIN ledger_entries e ON e.transaction_id=t.id
 WHERE t.status='COMPLETED' GROUP BY t.id
 HAVING count(e.id)<>2 OR coalesce(sum(CASE e.direction WHEN 'DEBIT' THEN -e.amount ELSE e.amount END),0)<>0
) x
UNION ALL
SELECT 'nonzero_currency_total',count(*) FROM (SELECT currency FROM accounts GROUP BY currency HAVING sum(balance)<>0) x
UNION ALL
SELECT 'currency_mismatch',count(*) FROM (SELECT transaction_id FROM ledger_entries e JOIN accounts a ON a.id=e.account_id
 GROUP BY transaction_id HAVING count(DISTINCT currency)<>1) x;
