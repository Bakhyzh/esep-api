# Deployment and local lifecycle

Use scripts/platform.py for init/build/start/stop/status. It verifies the pinned
frontend checkout, preserves private environment files, refuses legacy Kafka storage
recreation and does not delete volumes. Existing installations must keep their
original Keycloak credentials and use --identity-dir.

Default compose.platform.yaml + compose.m1.yaml runs one Ledger and two Gateways.
Monitoring and a second Ledger are optional; compose.monitoring-compact.yaml excludes
Ledger2. Grafana has a separate dashboards profile. The shared M1/4GB Docker VM
cannot safely run every heavy component and large integration suites together.

| Local endpoint | Purpose |
|---|---|
| localhost:18081 | Web and API edge |
| localhost:8180 | Development Keycloak |
| localhost:18082–18085 | Loopback-only service diagnostics |
| localhost:19090 /13000 /16686 | Optional Prometheus / Grafana / Jaeger |

No database, Kafka or Redis host ports are published. The external development
identity network is created by the separate Keycloak Compose project. Startup waits
for actual health and topic/ACL initialization. Financial readiness depends on its
database; a broker outage retains committed outbox records rather than stopping
money commits. Unknown/new identities remain denied until their projections arrive.

Production compose.production.yaml is never started by CI or the local lifecycle.
It requires per-service external env files, PKCS12 keys, CA material, pinned image
digests, HTTPS service routes and reviewed networking. Runtime and migration jobs
have different credentials. Keycloak production must use start/optimized mode and
correct HTTPS hostname, not start-dev. No Kubernetes or public server was deployed.
