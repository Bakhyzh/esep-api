DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='notification_runtime') THEN
  GRANT SELECT ON ALL TABLES IN SCHEMA public TO notification_runtime;
  GRANT INSERT,UPDATE ON notifications,processed_events TO notification_runtime;
  GRANT USAGE,SELECT ON SEQUENCE notifications_id_seq TO notification_runtime;
 END IF;
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='notification_projector') THEN
  GRANT SELECT,INSERT,UPDATE ON identity_principals,identity_bindings,identity_projection_state,identity_processed_events TO notification_projector;
  GRANT USAGE,SELECT ON SEQUENCE identity_bindings_id_seq TO notification_projector;
 END IF;
END $$;
