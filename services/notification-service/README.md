# Notification Service

Independent Java 21 / Boot 4.1.1 / JDBC application, notification_db owner.
Consumes esep.ledger.transfers.v1 and its private replay topic, stores a payload-hash
dedup marker and in-app notifications in one local transaction. It never changes
financial balances or uses another service's database. It has no user profile or
password storage; approved identity snapshots arrive independently from User.

GET /api/notifications preserves the previous list shape and adds readAt.
GET /api/notifications/unread-count and PATCH /api/notifications/{id}/read apply
only to the verified numeric current owner; ADMIN has no ownership override.
Read marking is idempotent. JWT RS256/JWKS, exact issuer/audience/time and intended
client-role checks are independent from Gateway. Unknown/disabled identities deny
access. Financial-event processing does not require identity to arrive first.

Duplicates retain one notification per event/recipient. Conflicting reused event IDs
are rejected. Transient DB errors roll back dedup and notification writes, retry
three times, then durably route to the dedicated .notification.DLT topic. Malformed
or permanently conflicting events go directly to DLT. A failed DLT send prevents
successful recovery/offset advancement. esep.notifications.replay.v1 accepts
reviewed original source events without republishing to every financial consumer;
the same durable eventId makes replay idempotent. Never fabricate missing money
facts to repair malformed events. Review the source outbox and fix the producer.

`../../mvnw -B -f pom.xml verify` runs real PostgreSQL/Kafka tests, including a
one-shot serialization failure injected in an isolated test DB, rollback, replay,
malformed-event DLT and cryptographically authenticated owner/read checks.

Root compose.platform.yaml uses separate migration/runtime/projector roles. Runtime
can write notifications/dedup but cannot alter identities. The projector can only
write identity projections. No internal DB ports are published. Extend .env.platform
with the three independently generated NOTIFICATION_* passwords listed in the
platform .env.example; do not rotate values on an initialized DB by replacing env.
On an existing synthetic platform volume, run the idempotent postgres-init.sql as
the local bootstrap operator before starting new services; it creates missing roles
and databases without deleting data or changing existing passwords.

The full local test uses real Keycloak, User, Ledger, Kafka and Notification:

```bash
python3 tests/e2e/identity-flow.py --identity-dir /path/to/original/infrastructure/keycloak \
  --notification-url http://localhost:18084 --broker-recovery
```

Kafka failure cannot undo a committed transfer. Notification queries retain already
stored data during broker outage and processing catches up after recovery. This
is at-least-once delivery, with transactional deduplication; no external email/push
or global exactly-once guarantee is claimed. Local isolated-network trust must be
replaced by production TLS/producer ACLs, monitoring, backup/replay policy and review.
