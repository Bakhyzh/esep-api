-- Local role separation. Existing migration owners can apply equivalent grants externally.
DO $$
BEGIN
 IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='ledger_runtime') THEN
   GRANT SELECT ON ALL TABLES IN SCHEMA public TO ledger_runtime;
   GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ledger_runtime;
   GRANT INSERT, UPDATE ON accounts, transactions, outbox_events TO ledger_runtime;
   GRANT INSERT ON ledger_entries TO ledger_runtime;
   -- Deliberately no principal/binding mutation or historical row DELETE grants.
 END IF;
END $$;
