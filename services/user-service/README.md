# User Service

Independent Boot 4.1.1 / Java 21 identity authority. Owns user_db profiles and
original numeric business IDs, exact verified issuer/subject bindings, lifecycle
versions and a transactional outbox. No passwords, hashing, financial accounts or
other service DB access.

`../../mvnw -B -f pom.xml verify` builds/tests independently. Docker context is this
directory. All DB/issuer/Kafka configuration is external; see application.yaml.
The root compose.platform.yaml starts a NEW synthetic environment using separate
migration/runtime roles and database ownership. Run infrastructure/platform/init-env.py
once and `docker compose -f compose.platform.yaml --env-file .env.platform up -d --build --wait`.
Existing volumes/real identities are never imported or overwritten automatically.

GET /api/auth/me (compatible response) and /api/users/me resolve the validated JWT
subject or provision a new verified human profile. Email collisions with an existing
profile are denied, never linked. PostgreSQL serializes first-login races; profile,
binding and outbox commit together. User IDs come from the authority's DB sequence,
never editable claims or client input. Token roles only come from esep-api client.
PATCH /api/users/me accepts displayName/version, with optimistic version conflicts.
ADMIN may POST /api/users/{id}/disable or /enable. No default ADMIN is created.

Outbox -> esep.users.v1 -> Ledger identity projection. Versioned full snapshots
allow duplicate, delayed and out-of-order delivery. Same event ID with different
payload or conflicting immutable mappings is rejected. Disabled state is enforced
by User immediately and by Ledger after projection commit. It is eventually
consistent; applications must allow bounded projection readiness on first login.

ADMIN-only /internal/identity/snapshots supports cursor pages. POST
/internal/identity/replay enqueues fresh-ID snapshots with the current source
version. Replaying never assigns or remaps IDs. Reconciliation compares complete
pages of (userId,issuer,subject,version,status) across services. Unknown preexisting
Ledger principals are never attached by event: they require approved migration.

Kafka delivery is at-least-once. Identity-topic producer trust is the isolated
network in local development; production must restrict write ACL to User and use
TLS/authentication. Ledger uses a separate restricted projector role, outside its
financial transaction. Consumer retries transient DB failures and uses a dedicated
ledger DLT for permanent contract/conflict failures; offsets follow durable handling.
The original standalone Ledger profile keeps synchronization disabled unless the
projection credentials are explicitly supplied.

`tests/e2e/identity-flow.py` drives actual Keycloak Code + PKCE, automatic provisioning,
Kafka projection, financial ownership, disable/enable, replay and reconciliation.
It removes only its temporary Keycloak users and retains synthetic financial/audit
rows. No password grant, token export or logs of credentials.
