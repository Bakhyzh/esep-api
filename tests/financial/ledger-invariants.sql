\set ON_ERROR_STOP on
-- Read-only assertions; run against the intended Ledger database only.
BEGIN READ ONLY;
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM accounts a LEFT JOIN ledger_entries e ON e.account_id=a.id
    GROUP BY a.id HAVING a.balance<>coalesce(sum(CASE WHEN e.direction='CREDIT' THEN e.amount ELSE -e.amount END),0)) THEN
  RAISE EXCEPTION 'Account balance differs from authoritative postings';
 END IF;
 IF EXISTS(SELECT 1 FROM transactions t LEFT JOIN ledger_entries e ON e.transaction_id=t.id
    WHERE t.status='COMPLETED' GROUP BY t.id HAVING count(e.id)<>2
      OR count(*) FILTER(WHERE e.direction='DEBIT')<>1
      OR count(*) FILTER(WHERE e.direction='CREDIT')<>1
      OR sum(CASE WHEN e.direction='CREDIT' THEN e.amount ELSE -e.amount END)<>0) THEN
  RAISE EXCEPTION 'Double-entry invariant failed';
 END IF;
 IF EXISTS(SELECT 1 FROM accounts WHERE type='USER' AND balance<0) THEN
  RAISE EXCEPTION 'Human account overdraft';
 END IF;
 IF EXISTS(SELECT 1 FROM transactions GROUP BY created_by,idempotency_key HAVING count(*)>1) THEN
  RAISE EXCEPTION 'Duplicated financial idempotency';
 END IF;
 IF EXISTS(SELECT 1 FROM accounts GROUP BY currency HAVING sum(balance)<>0) THEN
  RAISE EXCEPTION 'Currency balance conservation failed';
 END IF;
END $$;
SELECT 'financial_invariants_passed' AS result;
COMMIT;
