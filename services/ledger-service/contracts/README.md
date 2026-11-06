# Ledger contracts

`ledger-api.openapi.json` is the exported seven-path HTTP contract. Paths, DTOs,
status codes and headers retain the corresponding monolith financial semantics.
Only the host changes (local 8082). `/api/auth/me`, analytics and notifications remain
owned by the monolith and are not implemented here. Frontend routing is unchanged.

| Operation | Endpoint | Success / authorization |
|---|---|---|
| Open account | POST /api/accounts | 201 + Location; approved current user |
| List accounts | GET /api/accounts | 200; own, ADMIN may query another userId |
| Balance/account | GET /api/accounts/{id} | 200; own or ADMIN; foreign USER gets 404 |
| Close | POST /api/accounts/{id}/close | 200; existing ownership/status/zero rules |
| Transfer | POST /api/transfers | 201 + Location; source belongs to caller, including ADMIN |
| Top-up | POST /api/deposits | 201; ADMIN only, system funding account debited |
| Transaction | GET /api/transactions/{id} | 200; participant or ADMIN |
| History | GET /api/transactions | 200 paginated; current owner, existing date/zone filters |

POST transfer/deposit requires nonblank Idempotency-Key (max 64), scoped to the
original numeric user ID. Same key/fingerprint returns 200 + Idempotent-Replayed:
true, original transaction ID; different request returns 422 IDEMPOTENCY_KEY_REUSED.
No automatic client/proxy retries. Errors remain application/problem+json with
stable code: 400 validation, 401 invalid/missing JWT, 403 role/unbound identity,
404 hidden foreign/missing resource, 409 integrity/concurrency, 422 business rule.

Ledger independently validates RS256/JWKS, exact issuer, esep-api audience,
exp/nbf with default 60s skew, Bearer typ, nonblank opaque sub. Authorities only
come from resource_access.esep-api.roles USER/ADMIN. No trust in realm roles,
email, userId/local_user_id claims or X-User-Id. No login/password/HS256 decoder.
Logout/role changes take effect for old stateless JWTs after expiry/skew.

## Identity reference v1

`identity-reference.v1.schema.json` defines the minimal authenticated bootstrap
contract: schemaVersion, original userId, exact issuer/subject and approvedOperator.
It is **not** a client-facing API. In this phase the operator verifies the issuer,
Keycloak user UUID and ownership out of band, then uses bootstrap-identity.sql with
dry-run/apply and an auditable ticket. Runtime can SELECT projections but cannot
mutate them. System principals cannot authenticate even if an erroneous binding is
inserted. Unknown identity receives 403; no automatic linking/provisioning by email.

Future User Service provisioning must authenticate the producer/operator and
implement conflict-safe idempotent projection writes with durable audit/versioning.
Do not treat this JSON Schema as an implemented event pipeline or signed assertion.
Ledger allocates no business user IDs and never calls another DB or identity HTTP
service inside account/money transactions. Local fixtures reserve synthetic IDs;
real imports retain approved historical human/system IDs.

## Transfer event v1

Topic: esep.ledger.transfers.v1; record key: sender account ID; headers eventId and
eventType. Payload: transfer-completed.v1.schema.json. Stable UUID is allocated
once at outbox insertion, retained on retries. Identifiers and occurredAt retain
business semantics. Decimal amounts use the original JSON number wire format and
BigDecimal throughout Java/PostgreSQL; consumers must use exact decimal parsing.
The versioned topic isolates this fresh database from the old esep.transfers stream
whose numeric IDs may overlap. Never enable both streams for the same real accounts.

Only committed transfers emit this event, matching the original behavior. Top-ups
remain atomic double-entry transactions but do not emit a transfer event. Future
posting/lifecycle contracts for analytics are a separate consumer-first change.

Outbox insertion shares the money transaction and datasource. Async publisher
holds bounded row locks, sends with acks=all and producer idempotence, then records
published_at. Crash after send/ACK but before DB commit may duplicate delivery.
Consumers must deduplicate eventId transactionally. This is at-least-once, not
end-to-end exactly-once. Kafka partition order does not prove financial commit
order; parallel SKIP LOCKED publishers can reorder batches. This local deployment
runs one publisher instance. Multiple writers/publishers and strict global ordering
require a separately validated lease/fencing protocol.
