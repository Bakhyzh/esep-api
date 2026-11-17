# Current implementation

The repository contains five independent Java21 / Spring Boot4.1.1 applications:
User, Ledger, Notification, Analytics and Spring Cloud Gateway2025.1.4. The original
monolith remains available for controlled compatibility work. Its V1–V8 history
is preserved. The platform uses isolated development data; no production traffic
or existing monolith financial data has been migrated.

| Component | Implemented behavior | Verification |
|---|---|---|
| User | Profiles, generated numerical IDs, immutable issuer/subject binding, lifecycle, identity outbox | PostgreSQL/security tests and real PKCE/projection E2E |
| Ledger | Accounts, top-ups, transfers, postings, idempotency, outbox, fenced exports | Real PostgreSQL concurrency, money invariants, Kafka and RSA tests |
| Notification | Private notifications, unread/read, deduplication, retries and private DLT | Integration tests and actual transfer/outage/replay E2E |
| Analytics | Event-built read model, period reports, Redis generations, backfill/reconciliation | PostgreSQL/Kafka/Redis tests and actual fenced manifest comparison |
| Gateway/edge | Two Gateways, Nginx, independent JWT validation, shared limits, bounded forwarding | Actual browser, balancing, replica kills and financial settlement |
| Operations | Restricted roles, encrypted backups, isolated restores and PITR | Actual role, backup/restore and named-point WAL drills |
| Telemetry | Prometheus, Grafana, Loki, OTel and Jaeger | Actual metrics, dashboards, logs/traces and privacy canaries |
| Performance | Nine k6 scenarios and six fault groups | Measured artifacts in tests/performance/results |

Phases A–H were published through14b23f0e1d83c97a772d3153a0e194aaf7dea77f.
Phase I adds security backports, authenticated Kafka topic boundaries, durable
broker storage, production guards/templates, complete-stack CI and protected startup.
Final publication and current executed results belong in FINAL_ESEP_IMPLEMENTATION_REPORT.
A production deployment, cloud throughput, multi-host HA and real customer migration
remain outside the executed local verification.
