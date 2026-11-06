\set ON_ERROR_STOP on
\getenv user_migration_password USER_MIGRATION_PASSWORD
\getenv user_runtime_password USER_DB_PASSWORD
\getenv ledger_migration_password LEDGER_MIGRATION_PASSWORD
\getenv ledger_runtime_password LEDGER_DB_PASSWORD
\getenv ledger_projection_password LEDGER_IDENTITY_DB_PASSWORD
SELECT format('CREATE ROLE user_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD %L',:'user_migration_password') WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='user_migrator') \gexec
SELECT format('CREATE ROLE user_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD %L',:'user_runtime_password') WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='user_runtime') \gexec
SELECT format('CREATE ROLE ledger_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD %L',:'ledger_migration_password') WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='ledger_migrator') \gexec
SELECT format('CREATE ROLE ledger_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD %L',:'ledger_runtime_password') WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='ledger_runtime') \gexec
SELECT format('CREATE ROLE ledger_projector LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD %L',:'ledger_projection_password') WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='ledger_projector') \gexec
SELECT 'CREATE DATABASE user_db OWNER user_migrator' WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname='user_db') \gexec
SELECT 'CREATE DATABASE ledger_db OWNER ledger_migrator' WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname='ledger_db') \gexec
REVOKE CONNECT ON DATABASE user_db,ledger_db FROM PUBLIC;
GRANT CONNECT ON DATABASE user_db TO user_runtime;
GRANT CONNECT ON DATABASE ledger_db TO ledger_runtime,ledger_projector;
\connect user_db
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO user_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE user_migrator IN SCHEMA public GRANT SELECT,INSERT,UPDATE ON TABLES TO user_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE user_migrator IN SCHEMA public GRANT USAGE,SELECT ON SEQUENCES TO user_runtime;
\connect ledger_db
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO ledger_runtime,ledger_projector;
ALTER DEFAULT PRIVILEGES FOR ROLE ledger_migrator IN SCHEMA public GRANT SELECT ON TABLES TO ledger_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE ledger_migrator IN SCHEMA public GRANT USAGE,SELECT ON SEQUENCES TO ledger_runtime,ledger_projector;
