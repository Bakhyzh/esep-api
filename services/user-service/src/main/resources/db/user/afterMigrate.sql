DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='user_runtime') THEN
  GRANT SELECT ON ALL TABLES IN SCHEMA public TO user_runtime;
  GRANT INSERT,UPDATE ON users,user_identities,outbox_events TO user_runtime;
  GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO user_runtime;
  REVOKE INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER ON flyway_schema_history FROM user_runtime;
 END IF;
END $$;
