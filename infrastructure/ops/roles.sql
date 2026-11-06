\set ON_ERROR_STOP on
\connect postgres
SET log_min_duration_statement=-1;
SET log_min_error_statement='PANIC';
SET log_statement='none';
\getenv backup_password PLATFORM_BACKUP_PASSWORD
\getenv monitor_password PLATFORM_OPS_PASSWORD
\getenv restore_password PLATFORM_RESTORE_PASSWORD
SELECT format('CREATE ROLE platform_backup LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',:'backup_password') WHERE NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='platform_backup') \gexec
SELECT format('CREATE ROLE platform_ops LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',:'monitor_password') WHERE NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='platform_ops') \gexec
SELECT format('CREATE ROLE platform_restore LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',:'restore_password') WHERE NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='platform_restore') \gexec
GRANT pg_read_all_data TO platform_backup;
GRANT pg_monitor TO platform_ops;
GRANT CONNECT ON DATABASE user_db,ledger_db,notification_db,analytics_db TO platform_backup,platform_ops;
ALTER ROLE platform_backup CONNECTION LIMIT 2;
ALTER ROLE platform_backup SET lock_timeout='5s';
ALTER ROLE platform_restore CONNECTION LIMIT 2;
ALTER ROLE platform_ops CONNECTION LIMIT 4;
ALTER ROLE platform_ops SET statement_timeout='5s';
ALTER ROLE platform_ops SET idle_in_transaction_session_timeout='30s';
ALTER ROLE user_runtime CONNECTION LIMIT 10;
ALTER ROLE ledger_runtime CONNECTION LIMIT 30;
ALTER ROLE notification_runtime CONNECTION LIMIT 10;
ALTER ROLE analytics_runtime CONNECTION LIMIT 10;
ALTER ROLE ledger_projector CONNECTION LIMIT 8;
ALTER ROLE notification_projector CONNECTION LIMIT 4;
ALTER ROLE analytics_projector CONNECTION LIMIT 4;
SELECT format('ALTER ROLE %I SET statement_timeout=''5s''',role) FROM unnest(ARRAY['user_runtime','ledger_runtime','notification_runtime','analytics_runtime','ledger_projector','notification_projector','analytics_projector']) AS role \gexec
SELECT format('ALTER ROLE %I SET lock_timeout=''2s''',role) FROM unnest(ARRAY['user_runtime','ledger_runtime','notification_runtime','analytics_runtime','ledger_projector','notification_projector','analytics_projector']) AS role \gexec
SELECT format('ALTER ROLE %I SET idle_in_transaction_session_timeout=''30s''',role) FROM unnest(ARRAY['user_runtime','ledger_runtime','notification_runtime','analytics_runtime','ledger_projector','notification_projector','analytics_projector']) AS role \gexec
\connect user_db
ALTER DEFAULT PRIVILEGES FOR ROLE user_migrator IN SCHEMA public REVOKE INSERT,UPDATE ON TABLES FROM user_runtime;
CREATE SCHEMA IF NOT EXISTS monitoring AUTHORIZATION platform_bootstrap;
CREATE EXTENSION IF NOT EXISTS pg_stat_statements WITH SCHEMA monitoring;
GRANT USAGE ON SCHEMA monitoring TO platform_ops;
\connect ledger_db
ALTER DEFAULT PRIVILEGES FOR ROLE ledger_migrator IN SCHEMA public REVOKE USAGE,SELECT ON SEQUENCES FROM ledger_projector;
CREATE SCHEMA IF NOT EXISTS monitoring AUTHORIZATION platform_bootstrap;
CREATE EXTENSION IF NOT EXISTS pg_stat_statements WITH SCHEMA monitoring;
GRANT USAGE ON SCHEMA monitoring TO platform_ops;
\connect notification_db
CREATE SCHEMA IF NOT EXISTS monitoring AUTHORIZATION platform_bootstrap;
CREATE EXTENSION IF NOT EXISTS pg_stat_statements WITH SCHEMA monitoring;
GRANT USAGE ON SCHEMA monitoring TO platform_ops;
\connect analytics_db
CREATE SCHEMA IF NOT EXISTS monitoring AUTHORIZATION platform_bootstrap;
CREATE EXTENSION IF NOT EXISTS pg_stat_statements WITH SCHEMA monitoring;
GRANT USAGE ON SCHEMA monitoring TO platform_ops;
