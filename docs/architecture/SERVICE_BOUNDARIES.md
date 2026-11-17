# Service boundaries and contracts

| Owner | Authoritative data | Public API |
|---|---|---|
| User | Business user ID, profile, verified issuer/subject, lifecycle, identity outbox | /api/auth/me, /api/users/me |
| Ledger | Accounts, balances, transactions, postings, financial idempotency and outbox | /api/accounts, /api/transfers, /api/deposits, /api/transactions |
| Notification | Notification/read status, processed event hashes, identity projection | /api/notifications |
| Analytics | Account/posting projections, aggregate generations, identity projection | /api/analytics |
| Gateway | Routes, correlation IDs, limits and timeouts | Compatible /api routes only |

Ledger never queries user_db. Its restricted projector consumes User events and
stores only the stable business identity needed for authorization. Authenticated
clients cannot choose their numerical user ID. Email does not establish ownership.
System principals are separate from human identities.

Four versioned JSON Schemas are in contracts/events. Actual committed outbox
payloads are checked by tests/contracts/validate.py --live. Kafka keys provide
partition ordering, not a global order. Immutable fact IDs, content hashes and
identity versions handle duplicate/out-of-order delivery. A Kafka ACK followed by
an outbox database rollback can deliver duplicates: semantics are at least once.

Private retry/DLT topics are owned by the relevant consumer principal. Only User
may publish identity events; only Ledger may publish authoritative financial facts.
Notification/Analytics credentials cannot forge those producer contracts.

401 denotes invalid authentication; missing local identity is denied. Foreign
account access remains404. Changed data for an existing idempotency key remains422
IDEMPOTENCY_KEY_REUSED. ADMIN does not grant source-account ownership. Internal
write-fence/export/replay endpoints are not exposed through Gateway.
