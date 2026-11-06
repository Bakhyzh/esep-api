# ESEP Phase 2.3 — prepared local Keycloak cutover

Date: 2026-11-06, Asia/Almaty. Backend base: origin/main
`26777bcac0a08a2d0b9f2cd377be93f720bac1cb`.

## Result and scope

**Prepared local authentication acceptance passed, including actual frontend E2E.**
Keycloak is authoritative for the running isolated esep-cutover-local environment.
This is not a production deployment or a claim that real customer identities have
been migrated. Existing-user migration was rehearsed with explicitly verified
local fixtures; real-user linking still requires independent owner verification.
No standing ADMIN/default business credentials were introduced into the accepted
local database. Temporary Keycloak test identities/providers were removed.

The original worktree, db-lab and seven original architecture documents remain
untouched and outside these commits. The original ESEP PostgreSQL container
`ee16bc0c7ce5f84a1260eea09b2bc0f045f1e7a44e891194bb5487618f55a739`
remains stopped with volume esep-api_esep-pgdata. No SQL, migration, restart or
mount of that original financial database was performed. Keycloak's existing
realm/database volume was retained; no realm reset, DB cleanup or volume deletion.
No Phase 3, Kubernetes, service extraction or Load Balancer work.

## Audit and reused implementation

Reviewed the seven original architecture/roadmap documents, Phase 2.1/2.2 reports,
identity runbook, actual security/authentication/converter/profile configuration,
Flyway V8, demo seeding, financial tests, Compose/deployment workflows, OpenAPI and
Postman. Verified origin/main after fetch and isolated work from unrelated changes.
The verified frontend repository is Bakhyzh/esep-web, separate clean worktree from
origin/main `65bf645`; its original revert/intro-author worktree was preserved.

Phase 2.2 already implemented RS256-only Nimbus/JWKS, strict issuer/esep-api
audience, expiration/not-before and Bearer typ, exact issuer/subject resolution,
client-scoped roles, numeric CurrentUser adaptation, preserved /me, denied legacy
password routes in OIDC mode, V8 constraints and cryptographic tests. These were
reused rather than replaced. V1–V8 and financial implementation remain unchanged.
Legacy AuthService/TokenService/JwtProperties/encoder stay for compatible-build
rollback and legacy financial regression fixtures; they are not an authentication
fallback in the migrated environment. No new dependency/version upgrades in Java.

## Implemented changes and flow

- Explicit local-keycloak profile includes keycloak-integration **without dev**,
  uses only normal Flyway migrations and enables safe new-user provisioning.
  Exactly one RS256 decoder; production remains guarded against this profile.
- IdentityProvisioningService creates a NULL-password USER and V8 mapping in one
  PostgreSQL transaction only for an already verified token with permitted client
  role and email_verified=true. Existing normalized email collision denies access;
  there is no email-based linking. Human provisioning rejects @esep.internal.
- Transaction advisory lock per issuer/sub serializes first login across app
  instances; unique email and V8 constraints arbitrate conflicting subjects. New
  profiles receive no account, funds or local ADMIN role automatically.
- Operator SQL supports dry-run rollback, explicit apply, repeat no-op and conflict
  rejection. Approval operator/time remains in V8 linked_by/linked_at. Old user IDs,
  hashes, accounts, balances, transaction/ledger and idempotency scope are retained.
- DemoDataSeeder and legacy AuthRateLimitFilter excluded from OIDC mode. Keycloak
  brute-force policy remains the login protection; Redis analytics is unchanged.
- Isolated Compose project, loopback ports, persistent independent PostgreSQL/Kafka
  volumes and explicit identity network; canonical external issuer stays unchanged
  while container JWKS uses the identity network hostname.
- OIDC OpenAPI hides legacy password endpoints, describes authorizationCode and
  enables Swagger PKCE; separate clean Postman OAuth2/PKCE collection. Exact tool
  callbacks still need separately approved clients; they were not auto-allowlisted.
- Backend SSH deployment and frontend public Pages deployment now require explicit
  manual workflow inputs. Pushing these commits does not deploy either site/API.
- Detailed startup, activation, migration, failure behavior, rollback and production
  gates in [local cutover runbook](../runbooks/keycloak-local-cutover.md).

```text
Browser esep-web -> Keycloak authorize(code + PKCE S256 + state/nonce)
Keycloak authentication / required password update -> code callback
keycloak-js -> code exchange -> in-memory access/refresh tokens
Browser -> Bearer access token -> Spring RS256/JWKS/issuer/audience/time/roles
-> exact V8 mapping (or safe new-user provisioning) -> original numeric user ID
-> existing service ownership checks -> unchanged financial transaction boundary
```

## Frontend integration

Implemented in the actual esep-web repository with supported keycloak-js 26.2.5.
Explicit VITE_AUTH_MODE=keycloak selects the local OIDC provider and replaces login/
register credential forms with the identity-provider redirect. Code flow, mandatory
S256, query callback processed before HashRouter, state/nonce, in-memory tokens,
legacy token storage removal, token refresh before calls/expiry, refresh-failure
logout, startup error/retry UI, /me numeric identity and Keycloak logout are covered.
API refresh failure sends no financial request; no automatic transfer retries or
idempotency key rewriting. Existing legacy/Pages builds keep their prior mode.
The local root-path OIDC build is not an approved production Pages configuration.

Chromium drove the actual sign-in page, observed code/S256 challenge, completed
Keycloak UPDATE_PASSWORD, received the code, called the real API, checked the
original numeric /me ID, checked no tokens in local/session storage, displayed the
signed-in dashboard and logged out. It used no mocked HTTP routes/JwtDecoder.
Traces, screenshots, video and storage-state exports were disabled for this test.

## Migration results and corrections

On the accepted fresh local database esep_oidc, a legacy owner/account existed
before OIDC linking. Matching verified email alone received 403. SQL dry-run
created no mapping; approved apply and repeat linked exactly the old user ID.
The old account and retained password marker remained unchanged. A second new
identity provisioned separately and could neither read nor spend that account.
Financial transfer/replay and ADMIN deposit then passed with real Keycloak tokens.

A first draft profile incorrectly included dev: Spring profile precedence caused
R__dev_seed to run in the **isolated rehearsal database esep**. Acceptance caught
the unintended demo ADMIN. Fixed the profile group to exclude dev entirely and
added LocalKeycloakProfileTest checking active profiles, Flyway history, absent
seed ADMIN and absent demo/rate-limit beans. In the unused rehearsal database,
the three seed-only rows were disabled (NULL hashes, USER role), after checking
no account/mapping referenced them. No rows or financial data were deleted.
A separate fresh esep_oidc database became the accepted environment; the full
Compose/browser/security sequence passed there with no dev seed or default ADMIN.
The old rehearsal DB and its financial audit fixtures were retained.

Initial browser run lacked Chromium; installed Playwright Chromium. The next run
exposed a test selector mismatch on Keycloak's password-update Submit button;
corrected the selector and reran successfully. These failures were not reported
as passes. Actual UI and backend security requirements were not weakened.

## Executed verification

Environment: Java 21.0.2, Spring Boot 4.1.1, PostgreSQL 17.11, Redis 7.4,
Kafka 3.9.1, existing local Keycloak 26.7.0, Chromium 156 ARM64.
rtk was unavailable; standard commands used. No delegated agents used.

| Verification | Actual result |
|---|---|
| Final ./mvnw -B verify | PASS: 248 tests, 0 failures/errors, 1 opt-in live test skipped; 247 executed |
| backend-smoke.py with original identity-dir | PASS: live KeycloakLiveIntegrationTest, 1 test executed, no failure/error/skip; temporary identity removed |
| Provisioning integration | PASS: 16 concurrent first logins yield one local user/mapping; email collision and different-subject same-email races deny association; NULL hash, local USER only |
| LocalKeycloakProfileTest | PASS: no dev profile/seed ADMIN, one decoder, no demo or legacy limiter beans |
| KeycloakSecurityIntegrationTest | PASS: real RSA/HTTP JWKS signature verification and full Spring filter chain, not mocked JWTs |
| cutover-smoke.py + frontend | PASS against actual container API: approved link, preserved IDs/account, collision denial, provisioning, USER/ADMIN, ownership, transfer/replay/insufficient funds |
| Chromium OIDC acceptance | PASS: 1 browser E2E including password update, code+S256, real /me original ID, dashboard, in-memory token check and logout |
| Actual logout and role change | PASS: refresh rejected after logout; existing signed access token remains valid; removed role rejected in freshly issued token |
| Actual signing key rotation | PASS: temporary new RSA provider changed kid; Nimbus fetched JWKS; old retained and new keys both validated; provider removed after test |
| Actual Keycloak restart | PASS: existing/new login token and original local ID still work; realm/identity/key data persisted |
| rollback-smoke.py | PASS: separate temporary loopback legacy JVM, register/login/me, NULL-password login rejection, accounts/transactions/ledger snapshot unchanged; process stopped |
| Frontend npm run check | PASS: lint, 27 unit tests, TypeScript and production build |
| Frontend VITE_AUTH_MODE=keycloak npm run build | PASS: explicit OIDC mode build |
| docker compose config | PASS: default, isolated local and production syntax; production uses dummy validation variables only |
| Docker image / Compose up --wait | PASS: image built; container API/PostgreSQL/Redis/Kafka healthy; internal JWKS networking proved by real tokens |
| Flyway integrity | PASS: V1–V8 byte-for-byte unchanged against base; V7→V8 preservation/constraints suite passes |
| Financial regression | PASS: full existing account, balance, transfer/concurrency, insufficient funds, double-entry/idempotency, rollback, transactional outbox/Kafka and Redis analytics suites |

Full financial regressions use real PostgreSQL/Kafka/Redis Testcontainers. Live
cutover additionally exercises the same service paths under real Keycloak tokens.
Financial code was not modified. A compatible legacy-mode rollback was exercised
against the isolated database with an identical financial snapshot before/after.
This does not claim full real-user rollback for Keycloak-only NULL-password rows.

Security tests executed: valid RS256, wrong RSA signature, wrong issuer/audience,
expired/future-nbf/missing-expiry/missing-sub/wrong-typ, missing/malformed/foreign
roles, unknown identity, forged email/local-ID claims, mapping constraints,
USER/ADMIN, foreign account/spending, legacy HS256 rejection and denied password
routes. Existing legacy mode regression tests still pass.

## Security findings and known limits

- Stateless JWT logout/role revocation does not invalidate already signed tokens.
  Live tests confirmed refresh/session revocation and fresh role enforcement;
  maximum configured access window is 300 seconds plus 60-second validation skew.
- Local Keycloak uses development mode and HTTP loopback intentionally. Existing
  local dev DB credentials are not a production least-privilege/secrets design.
- SMTP action-email delivery, 15-minute action-link expiry/single-use negative
  tests, real owner verification/bulk migration, external TLS/proxy/MFA/HA/DR and
  production privilege isolation remain **not executed**. Local required password
  update and subsequent login with the new password were actually exercised.
- Swagger/Postman redirect/client activation is documented, not runtime-tested.
  It does not block the verified frontend flow; do not enable password grant.
- JWKS unknown-key/network failure remains fail-closed by Nimbus; live rotation and
  restart are tested, but a separate prolonged IdP/network outage chaos drill is
  not claimed. Cached public keys can validate existing JWTs while IdP is down.
- Temporary business identity cleanup leaves isolated financial/mapping audit
  fixtures referring to removed test subjects; they cannot grant access. No real
  customer was silently linked, deleted or assigned privileges.

## Rollback and production prerequisites

See [runbook](../runbooks/keycloak-local-cutover.md). Stop cutover clients/API,
retain V8/mappings/volumes, and use the same compatible build with explicit legacy
configuration and matching frontend. Retained BCrypt users can log in; new NULL-
password identities need Keycloak continuity or a forward fix, not invented legacy
passwords. Prefer the last known compatible OIDC build for these users. Never
restore stale financial snapshots merely to undo authentication, reset sequences,
remove V8, replace mappings by email or rewrite balances.

Before production: separate HTTPS issuer/hostname/CORS/redirect configuration and
reviewed guard change, optimized Keycloak (no start-dev), external secrets, private
admin/management endpoints, restricted runtime/migration DB roles and mapping
writes, MFA policy, tested SMTP activation, verified owner manifest and explicit
ADMIN approvals, restored backups for both databases and rehearsed migration/
reconciliation/rollback covering every credential state. Production cutover is a
separate authorized action; no public server was contacted/deployed here.

## Git publication

Frontend implementation commit: a135211 (full SHA and remote verification recorded
in the publication follow-up). Backend implementation and final push confirmation
are recorded in a subsequent documentation-only publication commit, avoiding a
self-referential commit SHA. Both integrations use fast-forward/normal push only;
no force push, reset, destructive cleanup or branch-protection bypass.

### Confirmed publication record

- Backend implementation: `fbc30cab32e0ce9d453be32556c1d4b1fe78e09f`.
  Integrated into the existing clean main worktree by `git merge --ff-only`, normal
  push to origin/main succeeded. Independent `git ls-remote origin refs/heads/main`
  returned exactly that SHA before this documentation-only follow-up.
- Frontend: `a135211a977d6e3c0a66a2849b6bb52600e3b8e0` in Bakhyzh/esep-web.
  Normal fast-forward main integration/push succeeded; independent ls-remote
  returned exactly that SHA. Original frontend worktree/branch was not changed.
- Gitleaks 8.30.1 scanned both complete staged changesets with redacted output:
  **no leaks found**. Git staged diff whitespace checks passed in both repositories.
  Additional allowlisted log inspection found no complete JWT-shaped tokens in
  API, live/cutover, rollback or Maven verification logs. No .env or runtime test
  artifacts were staged. V1–V8, db-lab and the seven original audit documents were
  excluded from changes. No force push, hard reset or cleanup was used.
- Final local Compose services app/postgres/redis/kafka were running and healthy;
  Keycloak remained on the original persistent identity stack. Temporary rollback
  JVM was stopped. Frontend local Vite server remains available on localhost:5173.
- Backend production SSH and frontend Pages deploy jobs require manual opt-in;
  these pushes only trigger validation/build work, not a public deployment.
