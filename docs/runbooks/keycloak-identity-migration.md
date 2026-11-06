> Phase 2.3: for the prepared local cutover, provisioning, executable linking and activation,
> use [keycloak-local-cutover.md](keycloak-local-cutover.md). The Phase 2.2 trial below
> remains a historical/manual rollback reference.

# Phase 2.2: explicit identity linking and integration trial

This is a controlled LOCAL/TEST trial, not a production cutover. The default and
`prod` authentication remain legacy HS256. V1–V7 must remain byte-for-byte unchanged.
The original architecture audit is separate local work, not included in this change.

## Select exactly one authentication mode

| Profiles | Decoder / endpoints |
|---|---|
| Existing default/dev/test/prod without keycloak-integration | Existing HS256 decoder, registration/login retained |
| dev,keycloak-integration or test,keycloak-integration | One RS256/JWKS decoder, Keycloak-only access tokens |
| prod + keycloak-integration (even with dev) | Startup rejected |
| keycloak-integration without dev/test | Startup rejected |

In integration mode login/register handlers are retained but their HTTP routes are
denied (anonymous 401, authenticated 403); passwords are never forwarded to Keycloak.
The legacy encoder/service beans remain for compatibility but their HS256 tokens
are NOT accepted by the integration decoder. There is no fallback or dual acceptance.
Demo provisioning is disabled; explicitly forcing it on rejects startup. Existing
Redis cache and financial/Kafka code are unchanged. The old auth rate limiter no
longer protects the actual Keycloak login: use the realm's brute-force policy.

Do not run the trial against the existing ESEP financial database. Provision a
disposable copy or a fresh separate PostgreSQL database first and configure
DB_URL/DB_USERNAME/DB_PASSWORD explicitly. No actual production/staging DB was
migrated in this task; tests create their own PostgreSQL via Testcontainers.

Example JVM settings after provisioning an isolated trial DB on port 55433:

```bash
export SPRING_PROFILES_ACTIVE=dev,keycloak-integration
export DB_URL=jdbc:postgresql://localhost:55433/esep_oidc_trial
# Set DB_USERNAME / DB_PASSWORD locally for this isolated DB, never in Git.
export OIDC_ISSUER_URI=http://localhost:8180/realms/esep
export OIDC_JWK_SET_URI=http://localhost:8180/realms/esep/protocol/openid-connect/certs
./mvnw spring-boot:run
```

`dev` also enables the existing development user seed; it does NOT bind its users
to Keycloak. For repeatable automated testing prefer the `test` profile, which has
no dev seed. The legacy secret bean still exists; dev/test provide their existing
non-production key. Do not interpret that bean as permission to use HS256 in OIDC mode.

For a future containerized backend, keep the same canonical issuer and use the
internal JWKS URL `http://keycloak:8080/realms/esep/protocol/openid-connect/certs`
only after explicitly attaching the identity network. No existing Compose or
production files are changed by Phase 2.2.

## V8 and local user IDs

V8 adds `user_identities`: generated ID, FK `user_id`, exact case-sensitive
`issuer`, opaque `subject`, `linked_at`, and required operator reference `linked_by`.
Unique `(issuer, subject)` prevents an identity owning two local users; unique
`(user_id, issuer)` prevents silently replacing a user's identity in one realm.
The latter also indexes local user FK lookups. Empty/blank identity/operator values
are rejected. There is no cascade deletion of financial history.

No identity is created by Flyway or by authentication. Unknown identity or missing
ESEP roles receives 403; invalid JWT gets 401. An incoming email, email_verified,
local_user_id, realm-management role or unrelated client role cannot bind an account.
The system funding owner `system@esep.internal` is excluded from runtime resolution.

`users.password_hash` becomes nullable for eventual Keycloak-only profiles. Existing
values, numeric IDs, sequence state and financial references are untouched by V8.
Legacy login explicitly rejects NULL hashes, even if the supplied password matches
the timing dummy password. No production users are created or altered by this trial.

`/api/auth/me` keeps its JSON shape and local id/email/createdAt, but reports the
effective role from the verified principal. USER/ADMIN authorities come only from
`resource_access.esep-api.roles`; both are retained when present. Local users.role
is historical in OIDC mode and cannot elevate a token. Financial ownership checks
still use the original numeric user ID; ADMIN still cannot transfer someone else's funds.

## Approved migration procedure for existing users

1. Record an approved migration ticket/operator; take and restore-test a backup
   before changing a real environment. Inventory numeric users/accounts/created_by
   and keys. Exclude the technical funding principal and resolve collisions manually.
2. Independently verify the owner's identity. Create/locate the exact Keycloak user
   through a trusted Admin Console/API, record its immutable user ID (sub), realm
   issuer, enabled status and separately approved roles. A matching email alone,
   even verified, is not authorization to take over an existing financial account.
3. Ensure both unique mapping checks are empty or already identically approved.
   Never overwrite/reassign a binding with an upsert. Any conflict stops migration.
4. Run the following **review/dry-run** using an authorized DB operator and explicit
   psql variables, not string interpolation. Inspect the locked local row and ensure
   exactly one returned mapping. Start with ROLLBACK. Only replace ROLLBACK with
   COMMIT after independent approval; keep the resulting ID/operator/time in the
   migration record. This is not a public account-linking endpoint.

```sql
\set ON_ERROR_STOP on
BEGIN;
SELECT id, email, role FROM users WHERE id = :'local_user_id'::bigint FOR UPDATE;
SELECT user_id, issuer, subject FROM user_identities
WHERE (issuer = :'issuer' AND subject = :'subject')
   OR (user_id = :'local_user_id'::bigint AND issuer = :'issuer');
INSERT INTO user_identities (user_id, issuer, subject, linked_by)
SELECT id, :'issuer', :'subject', :'approved_operator'
FROM users WHERE id = :'local_user_id'::bigint AND email <> 'system@esep.internal'
RETURNING id, user_id, issuer, subject, linked_at, linked_by;
ROLLBACK;
```

5. User signs in through Authorization Code + PKCE S256. Verify `/me` returns the
   old local ID, existing account ownership and replay keys remain correct; another
   user cannot access/spend that account. Verify ADMIN separately, no default grants.
6. Existing BCrypt strings cannot simply be copied into Keycloak. Use independently
   verified activation/password reset with time-limited action links and configured
   SMTP in a later migration phase. No password grant, password proxy, plaintext
   export, or promise of transparent credential migration is provided here.

The application only READS approved mappings. DB roles allowed to write mappings
are operational authority; production hardening should restrict these writes to
migration operators. The current shared dev DB credential is not a least-privilege
production design. Large imports/bulk automation and admin tooling are deferred.

## Tests and actual Keycloak smoke

```bash
./mvnw -B verify
python3 infrastructure/keycloak/backend-smoke.py
```

Full verify uses real local HTTP JWKS and generated ephemeral RSA keys through the
actual Spring decoder/filter chain, plus PostgreSQL/Kafka/Redis Testcontainers.
It does not mock JwtDecoder. V7→V8 is tested with historical balances/postings,
snapshot equality and uniqueness/FK/CHECK enforcement; fresh V8 runs in Spring tests.
The existing transfer/concurrency/outbox suites remain in place.

Live smoke requires the Phase 2.1 stack to be healthy and its ignored `.env` available.
An existing Phase 2.1 client must first receive the `basic` default client scope:

```bash
python3 infrastructure/keycloak/migrate-subject-scope.py --identity-dir /absolute/path/to/esep-api/infrastructure/keycloak
python3 infrastructure/keycloak/migrate-subject-scope.py --identity-dir /absolute/path/to/esep-api/infrastructure/keycloak --apply
```

This is a narrow, explicit Admin API update; no realm deletion/reimport. Keycloak's
basic scope supplies the access-token sub; the backend refuses tokens without it.
See [Keycloak client-scope documentation](https://www.keycloak.org/docs/26.4.7/server_admin/).
In another worktree, pass its original directory, for example:

```bash
python3 infrastructure/keycloak/backend-smoke.py \
  --identity-dir /absolute/path/to/esep-api/infrastructure/keycloak
```

The script reuses Phase 2.1 Code+PKCE login helpers, creates one random temporary
Keycloak USER, and invokes `KeycloakLiveIntegrationTest` with the token in process
environment only. The Spring test uses a fresh Testcontainers business DB, creates
an account before the mapping and proves access is denied before explicit linking,
then allowed under the same numeric ID. The Keycloak identity is removed in finally.
No existing ESEP DB or user is involved. This test is skipped in default verify
unless `ESEP_KEYCLOAK_LIVE=true`; the live script enables it. Do not print/store tokens.
The smoke runner is not a frontend implementation or a general-purpose OIDC library.

## Frontend / Swagger / Postman transition

The current frontend still uses local login. A later frontend PR must use the
public esep-web client, Code + mandatory PKCE S256, callback
`http://localhost:5173/oidc/callback`, state/nonce and in-memory access tokens.
Send access token (not ID token) as Bearer. Do not derive ownership from email/sub
client-side; `/me` returns the local numeric ID. Preserve Idempotency-Key on retries.
Current OpenAPI endpoint inventory and legacy Postman login remain unchanged in
this phase. For OIDC trial Postman must use Authorization Code + PKCE with an
explicitly approved exact redirect URI; its default callback is NOT allowlisted.
Swagger OAuth redirect/PKCE needs its own explicit client/allowlist change; for now
paste an already acquired OIDC access token into the existing Bearer Authorize UI.
Never enable password grant to make either tool convenient.

## Rollback and remaining limits

Disable the integration profile and restart the same compatible build to return to
legacy mode; no financial data rollback or deletion of mappings is required.
Keep V8 and its history; no Flyway repair/clean/down SQL. If considering an older
pre-V8 binary, first audit Keycloak-only NULL password rows: old login code has not
been hardened for those rows. Prefer the current build in legacy mode or forward fix.
No users.id renumbering, balance UPDATE, mapping-by-email or destructive reset.

This phase does not enable production OIDC, migrate real users, install SMTP/MFA,
provide immediate token revocation, add service extraction or change Gateway/Kafka.
Short-lived JWTs can outlive role removal/logout until expiry. Unknown JWKS keys
fail closed; SQL/IdP outages are not converted into authenticated users.
