# Architecture decisions

Short records of non-obvious choices: the context, what was decided, and what it costs.

## D1. Work delivered as stacked branches, without pull requests

**Context.** Stage 8 and the follow-up parts were done autonomously; the owner asked not to open PRs.
Without merges `main` cannot move forward between parts.
**Decision.** Each part has its own branch, started from the previous part's branch:
`main → feature/redis-kafka → feature/cors-api`. Merge them in this order.
**Cost.** Reviewing `feature/cors-api` alone shows both parts until `feature/redis-kafka` is merged.

## D2. Cache-aside for analytics with generation-based invalidation

**Context.** Reports are read far more often than they change, and they change only when the user spends.
**Decision.** `AnalyticsCache` implements cache-aside manually (not `@Cacheable`):
key `analytics:{userId}:v{generation}:{report}:{resolved params}`, TTL 5 minutes.
After a transfer commits, the sender's generation counter is incremented (`INCR`): all their old keys
become unreachable at once and expire by TTL. No `SCAN`/`KEYS`, O(1) per transfer.
**Why not `@CacheEvict`.** Evicting "all keys of one user" needs either `allEntries = true`
(drops every user's cache) or a key scan.
**Details.**
- The key uses the *resolved* date range, so "last 30 days" asked on different days never collides.
- Only the sender is invalidated: spending counts `DEBIT` entries, the receiver's spending does not change.
- Redis is an optimization, not a dependency: read/write/invalidation errors fall back to the database
  (Redis timeout 500 ms).
- Race safety: a request that read the old generation writes its result under the old key, which nobody
  reads any more.
**Cost.** Stale data is possible only if invalidation itself fails (bounded by the 5-minute TTL).
Old keys occupy memory until they expire.

## D3. Invalidation in-process after commit, not through Kafka

**Decision.** `@TransactionalEventListener(AFTER_COMMIT)` on an in-process `TransferCommittedEvent`.
**Why.** Invalidating *before* commit lets a parallel request re-cache old numbers; invalidating through
the Kafka consumer adds seconds of staleness for the user who just paid.
**Cost.** With several app instances this still works (the counter lives in Redis), but a crash between
commit and listener leaves the cache stale until TTL.

## D4. Transactional Outbox with a polling publisher

**Context.** "Save the transfer, then send to Kafka" loses events when the app dies in between;
"send, then save" announces transfers that were rolled back.
**Decision.** The event is inserted into `outbox_events` in the same database transaction as the transfer
(`TransferEventWriter`, `Propagation.MANDATORY`). `OutboxPublisher` polls every second:
`SELECT ... FOR UPDATE SKIP LOCKED LIMIT 100`, sends each event synchronously (`acks=all`, idempotent producer),
then sets `published_at`.
- `SKIP LOCKED`: several instances can publish in parallel without waiting for each other.
- The batch stops at the first failed send, so later events never overtake an earlier one with the same key.
- Delivery is **at-least-once**: a crash after the broker ack but before the commit resends the event.
**Alternatives.** CDC (Debezium reading the WAL) has lower latency and no polling, but needs Kafka Connect
infrastructure; overkill for one service.
**Cost.** Up to ~1 s latency. Row locks are held while sending (bounded by the 5 s send timeout).
Published rows are never deleted yet (a retention job is a known TODO). Failed events retry forever
(`attempts`, `last_error` are visible for monitoring).

## D5. Event key and topic layout

`esep.transfers` with 3 partitions, key = sender account id: all events of one account are ordered.
`esep.transfers.DLT` with the same partition count (a dead record keeps its partition number).
Only transfers produce events; deposits are an admin operation and do not notify.

## D6. Idempotent consumer

**Decision.** `NotificationService` inserts `(consumer, event_id)` into `processed_events` with
`ON CONFLICT DO NOTHING` in the same transaction as the notifications. A duplicate finds the row and is skipped;
a failure rolls back both, so the retry starts clean. `UNIQUE (event_id, user_id)` on notifications is a
second safety net.
**Why the consumer name in the key.** Another consumer of the same topic must be able to process the same event.

## D7. Retries and Dead Letter Topic

**Decision.** `DefaultErrorHandler` with `FixedBackOff(1 s, 3 retries)`, then `DeadLetterPublishingRecoverer`
→ `esep.transfers.DLT` with exception headers. `InvalidEventException` (malformed JSON, missing fields)
is not retryable and goes to the DLT immediately. The payload is read as a `String` and parsed in the listener,
so a broken message never causes a deserializer error loop.
**Alternative.** Non-blocking retries (`@RetryableTopic` with retry topics) do not block the partition during
backoff, at the price of more topics and lost ordering. Blocking retries are fine for 3 short attempts.
**Cost.** While a record is being retried (~3 s), the rest of its partition waits. Nothing re-processes the DLT
automatically: it needs monitoring and a manual replay.

## D8. Notifications are stored, not sent

The consumer writes notifications to PostgreSQL and exposes `GET /api/notifications`.
E-mail/push would be another consumer of the same topic. The API is eventually consistent:
a notification appears about a second after the transfer.

## D9. Single-node Kafka in KRaft mode for local runs

`apache/kafka:3.9.1` without ZooKeeper. Two listeners: `kafka:9092` inside the compose network,
`localhost:9094` for the app started from an IDE. Replication factor 1 is for development only.

## D10. CORS inside Spring Security, exact origins, no credentials

**Decision.** `http.cors(withDefaults())` with a `CorsConfigurationSource` for `/api/**`;
allowed origins from `esep.cors.allowed-origins` (`CORS_ALLOWED_ORIGINS`, default `http://localhost:5173`).
Allowed request headers: `Authorization`, `Content-Type`, `Idempotency-Key`; exposed response headers:
`Location`, `Idempotent-Replayed`. `allowCredentials = false`, preflight cached for 1 hour.
**Why in Security and not only in MVC.** The browser's preflight `OPTIONS` carries no token: without the CORS
filter in the security chain it would be rejected with 401 and the real request would never be sent.
Error responses (401/403/404) also get CORS headers, so the UI can read them (e.g. to handle an expired token).
**Why no credentials.** The JWT travels in the `Authorization` header, not in a cookie; no cookies means no CSRF
and no need for `allowCredentials`.
**Cost.** Every new frontend origin (staging, prod) must be added to the config.

## D11. One error format: RFC 9457 + a stable `code`

**Decision.** Every error is `application/problem+json`: `type`, `title`, `status`, `detail` (the human message),
`instance`, plus `code` (enum `ErrorCode`), `timestamp` and, for validation, `errors: [{field, message}]`.
Spring MVC's own errors (unknown route, 405, 415, malformed JSON, missing header, type mismatch) are mapped
in `handleExceptionInternal`; security filter errors are routed through the same advice.
**Why `code`.** `detail` is for people and may change; clients switch on `code`
(e.g. `INSUFFICIENT_FUNDS` → highlight the amount field). Codes are only ever added, never renamed.
**Why `errors` is a list, not a map.** One field can break several rules; a list keeps all of them in order.
This replaced the earlier `{field: message}` map; no external client used it yet.
**"Message".** RFC 9457 calls it `detail`; a duplicate `message` field was not added.
**Cost.** Errors that never reach Spring MVC (e.g. a request rejected by the servlet container's URL firewall)
still use the container's default body.

## D12. Operation history endpoint for the UI

**Decision.** `GET /api/transactions?accountId=&from=&to=&zone=&page=&size=` returns the user's ledger entries
(a statement), newest first, as `PageResponse {content, page, size, totalElements, totalPages}`.
Offset pagination (page numbers for the UI). Dates are inclusive calendar days in a region time zone.
**Why not Spring Data `Page`.** Its JSON is an implementation detail; a small record is a stable contract.
**Cost.** Deep pages get slower (`OFFSET` scans skipped rows); keyset pagination on `(created_at, id)` is the
upgrade path. Two queries per page (`count` + data).

## D13. API surface is guarded by a test

`OpenApiDocsTest` compares the documented operations with an explicit list and requires a summary and error
responses on each. Adding or renaming an endpoint is a conscious change; the frontend relies on it.
