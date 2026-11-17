# Implemented architecture and deployment target

```mermaid
flowchart TB
  Web[Verified esep-web: Code + PKCE] --> Edge[Nginx local edge]
  Web --> KC[Keycloak OIDC]
  Edge --> G1[Gateway 1]
  Edge --> G2[Gateway 2]
  G1 & G2 --> U[User Service]
  G1 & G2 --> L[Ledger Service: optional replica 2]
  G1 & G2 --> A[Analytics Service]
  G1 & G2 --> N[Notification Service]
  U --> UD[(user_db)]
  L --> LD[(ledger_db: accounts + postings + outbox)]
  A --> AD[(analytics_db)]
  N --> ND[(notification_db)]
  U -- Identity outbox --> K[Kafka: authenticated principals + ACLs]
  L -- Committed financial outbox --> K
  K --> L & N & A
  A & G1 & G2 --> R[(Redis)]
  U & L & A & N & G1 & G2 --> O[Optional OTel / Prometheus / Loki / Jaeger]
```

Each business service owns a separate database and validates JWTs independently.
Four business databases share one PostgreSQL process locally. This is logical
ownership isolation, not four highly available database clusters. Keycloak has a
separate identity database and retains cryptographic keys and subjects.

The Gateway owns routing and edge policy; Ledger owns every authoritative money
change. Notifications and Analytics consume committed events independently.
No distributed financial transaction or synchronous identity call occurs inside
the money transaction. Replicas on one laptop do not establish host availability.

The production template requires reviewed private networking, external TLS
PostgreSQL/Kafka/Redis/Keycloak, immutable application images and external secrets.
It is configuration preparation, not an executed production deployment.
