# API Gateway

Independent Java21 / Spring Boot4.1.1 / Spring Cloud2025.1.4 WebFlux application.
The official [compatibility matrix](https://spring.io/projects/spring-cloud/)
confirms Boot4.1.x support since Cloud2025.1.2. The compatibility verifier remains
enabled and the actual application starts with the verified versions.

The Gateway owns no users, money, database, Kafka consumer or business logic.
Business services remain independent RS256 resource servers with owner checks and
local verified issuer/subject projections. Gateway forwards the original bearer,
not a trusted client-supplied user ID. Unknown identities remain denied by the
business service until User provisioning and Kafka projection complete.

| Public path | Owner | Compatibility |
|---|---|---|
| `/api/auth/me`, `/api/users/**` | User | Existing profile DTO; lifecycle privileges enforced downstream |
| `/api/accounts/**` | Ledger | Create/list/get/close semantics and ownership preserved |
| `/api/transfers`, `/api/deposits` | Ledger | Existing JSON, precise decimal strings and Idempotency-Key |
| `/api/transactions/**` | Ledger | Existing history/paging/query and transaction detail |
| `/api/analytics/**` | Analytics | Existing currency/period/time zone reports |
| `/api/notifications/**` | Notification | List, owner read/unread APIs |

There are **no internal operations routes**. `/internal/**` is denied even to an
ADMIN through Gateway. Old login/password/register endpoints are not routed;
clients use Keycloak Authorization Code+PKCE S256. No bearer credentials enter logs.

RS256/JWKS, exact issuer, esep-api audience, access-token type, subject, expiration
and not-before are independently checked. USER/ADMIN come only from esep-api's
client roles. Sessions, form login and HTTP Basic are disabled. JWTs can remain
valid after Keycloak logout/role changes until expiry (300s plus configured clock
skew); this is not immediate token revocation. User disable events independently
block a locally disabled identity. No gateway-only authorization guarantee.

CORS is an exact allowlist (local5173), no credential cookies. Correlation IDs are
bounded and sanitized; identity and forwarded headers from clients are removed.
Gateway-generated replica labels support local balancing checks. Headers copied
from a business response are deduplicated where required for browser CORS.
Route configuration is declarative so all default filters are applied. Requests,
including chunked bodies, are buffered **up to64KiB** before any downstream call;
excess data receives413 without forwarding.

Redis atomically limits by hashed issuer/subject, bounded route ID and read/write
class. Defaults600 reads /120 writes per60s per route, configurable externally.
429 includes Retry-After. Redis failure fails closed for mutations (503 before
forwarding) while reads can continue. This local disposable limiter resets quotas
on Redis loss; it is not a persistent abuse ledger. The decision error handler is
restricted to Redis and cannot retry a failed financial request. Connect timeout2s,
response timeout10s; **no automatic POST retries**. A caller may retry a transfer
with the same original idempotency key after an uncertain504/network result.

Existing business errors pass through with their original problem JSON. Gateway
adds429 RATE_LIMITED,503 DEPENDENCY_UNAVAILABLE,413 REQUEST_TOO_LARGE and transport
504. Authentication follows RFC6750 401/403. Clients must not infer that a504
means the financial transaction did not commit.

## Development

```sh
./mvnw -B -f services/api-gateway/pom.xml verify
docker compose -f compose.platform.yaml --env-file .env.platform build gateway
docker compose -f compose.platform.yaml --env-file .env.platform up -d --no-build --wait gateway
```

Loopback entrypoint `http://localhost:18080`; existing local Keycloak8180 remains
a separate provider. The image is non-root and has health/readiness and graceful
shutdown. Dependencies must be healthy initially. Losing Kafka does not invalidate
already-authorized transfers; Ledger retains its outbox. A missing business service
causes transport failure/timeouts, never a local financial fallback.

In the verified esep-web worktree, copy `.env.platform.example` to an ignored local
environment file or set its variables, then run Vite on localhost5173. Supported
keycloak-js keeps tokens in memory and uses Code+PKCE. Startup resolves `/me` first,
then waits at most30s for the three owner projections before mounting the dashboard.
Permanent profile/HTTP failures show a retry/sign-out state, not a redirect loop.
Analytics has a manual refresh for eventual updates.

Actual integrated synthetic smoke, including the real browser:

```sh
python3 tests/e2e/identity-flow.py \
  --identity-dir ../esep-api/infrastructure/keycloak \
  --notification-url http://localhost:18084 \
  --analytics-url http://localhost:18085 \
  --gateway-url http://localhost:18080 \
  --frontend-dir ../esep-web-platform
```

The script restricts targets to known loopback ports, uses temporary Keycloak
identities with explicit roles, makes no SQL identity assignments, clears browser
artifacts/temporary credentials and retains synthetic financial/audit history.
Internal fenced backfill/reconciliation calls stay on loopback service ports.

Rollback changes only the frontend's VITE_API_URL/explicit auth deployment mode.
Route a dataset to its actual authority; a monolith with different data is not a
financial fallback. Keep all databases, identity mappings, migrations and outboxes.
This local setup does not switch production traffic or claim public TLS/security
hardening, multi-host availability or production financial-customer readiness.
