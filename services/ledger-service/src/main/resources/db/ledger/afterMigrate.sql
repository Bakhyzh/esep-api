-- Local role separation. Existing migration owners can apply equivalent grants externally.
DO $$
BEGIN
 IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='ledger_runtime') THEN
   GRANT SELECT ON ALL TABLES IN SCHEMA public TO ledger_runtime;
   REVOKE ALL ON SEQUENCE ledger_identity_bindings_id_seq FROM ledger_runtime;
   GRANT USAGE, SELECT ON accounts_id_seq,transactions_id_seq,ledger_entries_id_seq,outbox_events_id_seq TO ledger_runtime;
   GRANT INSERT, UPDATE ON accounts, transactions, outbox_events TO ledger_runtime;
   GRANT INSERT ON ledger_entries TO ledger_runtime;
   GRANT UPDATE ON ledger_control TO ledger_runtime;
   -- Deliberately no principal/binding mutation or historical row DELETE grants.
 END IF;
 IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='ledger_projector') THEN
   GRANT SELECT,INSERT,UPDATE ON ledger_principals,ledger_identity_bindings,identity_projection_state,identity_processed_events TO ledger_projector;
   REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM ledger_projector;
   GRANT USAGE,SELECT ON ledger_identity_bindings_id_seq TO ledger_projector;
 END IF;
END $$;
