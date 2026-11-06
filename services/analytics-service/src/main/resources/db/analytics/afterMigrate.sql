DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='analytics_runtime') THEN
  GRANT SELECT ON ALL TABLES IN SCHEMA public TO analytics_runtime;
  GRANT INSERT,UPDATE ON analytics_accounts,analytics_transactions,analytics_entries,analytics_generations,processed_events TO analytics_runtime;
 END IF;
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='analytics_projector') THEN
  GRANT SELECT,INSERT,UPDATE ON identity_principals,identity_bindings,identity_projection_state,identity_processed_events TO analytics_projector;
  GRANT USAGE,SELECT ON SEQUENCE identity_bindings_id_seq TO analytics_projector;
 END IF;
END $$;
