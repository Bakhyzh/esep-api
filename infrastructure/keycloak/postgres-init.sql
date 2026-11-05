-- A new dedicated volume runs this automatically. No ESEP connections.
-- SQL instead of a bind-mounted executable: portable to Docker Desktop mount policies.
\set ON_ERROR_STOP on
\getenv kc_user KEYCLOAK_DB_USER
\getenv kc_password KEYCLOAK_DB_PASSWORD

SELECT format('CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION',
              :'kc_user', :'kc_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'kc_user')
\gexec
SELECT format('CREATE DATABASE keycloak_db OWNER %I', :'kc_user')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'keycloak_db')
\gexec
REVOKE CONNECT ON DATABASE keycloak_db FROM PUBLIC;
GRANT CONNECT ON DATABASE keycloak_db TO :"kc_user";
