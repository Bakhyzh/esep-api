# ESEP Phase 2.2 — Keycloak integration and identity mapping

Дата: 2026-11-06. Implementation base: origin/main
`3455c690decc1d9eec9659b62d6f4a65c3f4c3a0` (Phase 2.1).
Implementation worktree: `/private/tmp/esep-api-phase22-20261106`,
branch `feat/keycloak-identity-mapping`.

## Result and scope

Implemented and validated an opt-in Keycloak Resource Server inside the existing
monolith. Default and production authentication remain HS256. No microservice
extraction, Load Balancer, Phase 2.3, real-user migration or production deployment
was performed. The original working directory, db-lab and seven untracked
architecture documents were read/preserved and are not part of this commit.

The mode switch selects exactly ONE decoder: legacy without `keycloak-integration`,
RS256/JWKS with it. Integration additionally requires active dev/test and refuses
prod even when dev is also active. Demo provisioning must be disabled.

## Changed files

New production files:

- `src/main/java/com/esep/security/KeycloakProperties.java`
- `src/main/java/com/esep/security/KeycloakSecurityConfig.java`
- `src/main/java/com/esep/security/KeycloakJwtConverter.java`
- `src/main/java/com/esep/user/UserIdentity.java`
- `src/main/java/com/esep/user/UserIdentityRepository.java`
- `src/main/java/com/esep/user/IdentityResolutionService.java`
- `src/main/resources/application-keycloak-integration.yaml`
- `src/main/resources/db/migration/V8__external_user_identities.sql`

Modified production files:

- `src/main/java/com/esep/security/SecurityConfig.java` — explicit mode selection,
  deny password routes in integration, role gate before business controllers.
- `src/main/java/com/esep/security/CurrentUserAuthentication.java` — multiple authorities.
- `src/main/java/com/esep/security/CurrentUser.java` — identity-resolution documentation.
- `src/main/java/com/esep/user/User.java` — nullable local password mapping.
- `src/main/java/com/esep/auth/AuthService.java` — NULL password rejection and
  effective-principal role in `/me`, with unchanged response shape/local numeric ID.
- `src/main/java/com/esep/auth/AuthController.java` — `/me` principal adapter.

Tests:

- New `src/test/java/com/esep/security/KeycloakSecurityIntegrationTest.java`.
- New `src/test/java/com/esep/security/KeycloakProfileGuardTest.java`.
- New `src/test/java/com/esep/security/KeycloakLiveIntegrationTest.java`.
- New `src/test/java/com/esep/user/IdentityMappingMigrationTest.java`.
- Modified `src/test/java/com/esep/auth/AuthServiceTest.java` — NULL-hash timing-dummy regression.

Infrastructure/docs:

- New `infrastructure/keycloak/backend-smoke.py` — real Code+PKCE to Spring test.
- New `infrastructure/keycloak/migrate-subject-scope.py` — explicit local scope migration.
- Modified `infrastructure/keycloak/realm/esep-realm.json` — default `basic` scope.
- Modified `infrastructure/keycloak/validate.py` — require basic and nonempty token sub.
- Modified `infrastructure/keycloak/README.md` — scope migration and trial reference.
- New [identity migration runbook](../runbooks/keycloak-identity-migration.md).
- This report.

No pom/dependency changes, old migration edits, financial/Kafka/Redis code changes,
existing Compose changes or production configuration changes were required.

## Flyway V8

`user_identities` contains generated id, user_id FK to users, issuer, subject,
linked_at and linked_by (required operator reference). UNIQUE(issuer,subject) and
UNIQUE(user_id,issuer), blank-value checks. Authentication performs read-only exact
lookup; no email matching, provisioning or user creation on login.

`users.password_hash` becomes nullable; existing hashes and numeric IDs are not
rewritten. No mapping is seeded. Original V1–V7 were checked byte-for-byte against
origin/main and are unchanged. V8 was applied only to disposable Testcontainers DBs
in this task, not to the existing ESEP PostgreSQL.

The V7→V8 test first creates the real V7 schema, inserts historical funding/transfer
postings with old numeric owners and balances, then verifies JSON snapshots of
users/accounts/transactions/entries/outbox remain equal after exactly one new
migration. It checks Flyway validate plus uniqueness, FK and CHECK SQLSTATEs.
Full Spring tests also run fresh V1→V8 migration and Hibernate validate.

## Security and compatibility

- NimbusJwtDecoder uses actual HTTP JWKS and an RS256-only allowlist.
- Standard issuer and timestamp validators preserved, including default clock skew;
  audience must include esep-api, exp and nonblank opaque sub are required.
- Keycloak access-token claim typ must be Bearer. ID-token/wrong-audience tokens
  are rejected. No trust in email, local_user_id or unverified JWT content.
- Only USER/ADMIN from resource_access.esep-api.roles become authorities;
  unrelated/realm-management roles do not grant access. Both authorities preserved;
  CurrentUser uses ADMIN precedence for existing business checks.
- Valid but unlinked or roleless identity receives 403, invalid token 401. The
  funding system user is excluded from local identity resolution.
- Stateless security and existing ownership checks retained. ADMIN can deposit
  but cannot spend another user's funds. OIDC read-only resolution happens before
  service money transactions, not inside the transfer's financial commit boundary.
- Legacy login/register code and TokenService remain. Their routes are denied only
  in the opt-in integration mode; legacy tokens never fall back into RS256 mode.
- Existing null-password profiles cannot authenticate with the timing dummy password.
- Demo mutation/funding disabled in integration; Redis rate limiter remains for
  legacy endpoints, not as a claimed protection for Keycloak's login page.
- `/me` returns local id/email/createdAt and effective token role. Local stored
  role/hash remain unchanged. JWT role changes take effect after a fresh token.

## Runtime discovery and corrected Phase 2.1 gap

The first live test failed with the strict required-claims check. Safe inspection
of allowlisted token metadata showed RS256, correct issuer/audience and exp,
but **no sub**. The Phase 2.1 esep-web client had omitted Keycloak's `basic` default
scope, which supplies access-token sub. Previous infrastructure smoke had not
checked this claim. The backend validator was NOT weakened to work around it.

Corrections:

1. Added basic to realm JSON for fresh installations.
2. Added a dry-run-first, idempotent local Admin API scope migration for existing
   realm (startup import skips existing realms). No realm deletion or DB reset.
3. Added sub/basic assertions to the existing infrastructure validator.
4. Applied only that scope assignment to the local esep-web, reran migration to
   confirm no-op behavior, obtained a new Code+PKCE token and reran Spring live test.

The migration uses Admin Console Code+PKCE, never password grant. It changes no
ESEP user/account IDs. Source: [Keycloak subject mapper/basic scope](https://www.keycloak.org/docs/26.4.7/server_admin/).

## Commands and actual test results

Environment: Java 21.0.2, Spring Boot 4.1.1, PostgreSQL 17.11 containers,
local Keycloak 26.7.0. Maven wrapper and existing dependencies reused.
`rtk` absent; ordinary shell used without installing it.

| Command / check | Actual result |
|---|---|
| `./mvnw -B -DskipTests package` | BUILD SUCCESS, compilation/package only; not claimed as tests |
| `./mvnw -B -Dtest=KeycloakProfileGuardTest,KeycloakSecurityIntegrationTest,IdentityMappingMigrationTest test` | 12 tests, 0 failures/errors, 0 skipped |
| `./mvnw -B verify` | BUILD SUCCESS; 244 tests, 0 failures/errors, 1 intentionally skipped live test (243 executed) |
| Initial `backend-smoke.py --identity-dir <original Phase2.1 directory>` | FAILED: token without sub; temporary identity removed; no commit/push performed at that point |
| `migrate-subject-scope.py --identity-dir <original directory>` | Dry-run reported missing basic; no mutation |
| Same command with `--apply` | PASS: only basic scope attached to local esep-web |
| Same command again without apply | PASS: already assigned, no change |
| Corrected `backend-smoke.py --identity-dir <original directory>` | BUILD SUCCESS; live Spring test 1 run, 0 failures/errors/skips; temporary identity removed |
| Updated Phase 2.1 `validate.py` using the original ignored env file | PASS: discovery/JWKS, basic/sub, roles/audience, Code+PKCE and negative flow checks; temporary identities removed |
| Explicit Compose config with original env file | Exit 0, no credentials printed |

All relevant existing financial suites ran in full verify: TransferConcurrencyTest
(5), TransactionProcessorTest (15), TransactionServiceTest (5), LedgerTransactionTest
(10), outbox/Kafka integration (5), existing SecurityIntegrationTest (12), API
contract, account, analytics/cache, rate-limit and demo tests. No tests were removed.

New cryptographic/authorization coverage: valid token and original ID, real RSA
signature failure, HS256 rejection in integration mode, wrong issuer/audience,
expired JWT, future nbf, missing expiry, blank sub, ID-token typ, unknown identity,
email/local-ID forgery, missing/malformed/other-client roles, USER/ADMIN, foreign
accounts/listing/spending, authorized transfer and idempotent replay.

Live test used the actual Phase 2.1 Keycloak issuer/JWKS and a temporary USER created
with random password, signed in via Code+PKCE. It used a separate Testcontainers
business database, not the existing esep DB. An account existed before linking;
access was 403 before mapping, 200 after mapping with the same numeric ID, and 404
for another user's account. Password/hash preserved. No mocked JwtDecoder or MockMvc
jwt() was used for token validation. Actual browser engine UI is still not tested.

Generated Maven logs/reports/JARs stay outside version control. Tokens are passed
only in process environment, not command-line properties/files or report text.

## Explicit identity migration and remaining risks

Use the [runbook](../runbooks/keycloak-identity-migration.md): independently verified
owner, trusted Keycloak user ID/issuer, approved operator, backup/restore rehearsal,
dry-run transaction, review uniqueness, explicit INSERT and verified `/me`/ownership.
No public linking endpoint or automatic account attachment exists.

Existing BCrypt credentials are not transparently imported. Real users need a
verified activation/reset process and SMTP setup later; this task did not migrate
real users. Restrict mapping writes to authorized DB operators in production.
Default dev credentials/schema permissions are not production least privilege.
Other remaining limits: JWT revocation latency, IdP/DB outages, frontend OIDC
callback/session handling, Swagger/Postman redirect configuration, production
security/deployment and operational migration automation.

## Rollback

Turn off keycloak-integration and restart this compatible build to return to
legacy-only authentication. Keep V8/mappings; never edit V1–V7, Flyway repair/clean,
reset user sequences or update balances for auth rollback.
Pre-V8 binaries are unsafe for newly introduced NULL-password profiles unless
those states are audited first; prefer current-build legacy mode/forward fix.
Local scope assignment is additive and safe to retain. Reverting source JSON does
not rewrite an existing realm. No financial data was changed by this task.

## Git publication

Implementation commit SHA: pending creation after final diff/secrets review.
Push status: not yet attempted at report preparation; no publication is claimed here.
This section will record the verified implementation SHA after successful push.
Only Phase 2.2 code/tests/runbook/infrastructure corrections and this report are
eligible; the seven original untracked audit documents and db-lab are excluded.

## Recommended next step

Review the opt-in trial and approved linking procedure with existing-user fixtures.
Plan a separate frontend/activation/cutover task only after review. Do not enable
production OIDC or begin microservice extraction as part of Phase 2.2.
