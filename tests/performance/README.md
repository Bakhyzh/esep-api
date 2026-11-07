# Isolated platform load and resilience laboratory

This laboratory creates eight new synthetic identities/accounts, funds exactly
800.0000 KZT through the existing authorized API, then uses real Keycloak Code +
PKCE and refresh tokens. It refuses remote API/Docker targets and the wrong volume.
No existing accounts are imported. Synthetic financial audit history is retained;
temporary Keycloak identities are deleted in `finally`.

Prerequisites: running platform/Keycloak, private mode-0600 `.env.platform`, Python3,
Docker Compose, Prometheus at19090 and k6 2.3.0 (or pinned Docker engine). Stop the
optional Grafana UI during measurements. On the4GB Docker VM use compact monitoring.
For replica faults, pause optional Loki/Jaeger/Collector before starting Ledger2;
keep Prometheus and exporters running. This is a resource constrained shared host.

```sh
python3 tests/performance/validate.py
python3 tests/performance/run.py --identity-dir ../esep-api/infrastructure/keycloak
python3 tests/performance/run.py --identity-dir ../esep-api/infrastructure/keycloak --cases baseline --engine docker
python3 tests/performance/run.py --identity-dir ../esep-api/infrastructure/keycloak --cases balancing --faults
```

The default nine workloads are bounded:1–8 VUs,20–35 seconds each, except a300-second
soak. Increase load only after inspecting CPU/memory/DB budgets and exact money
invariants. Stress/spike permit and separately count429. Duplicate-payload conflicts
must return the existing422 `IDEMPOTENCY_KEY_REUSED` contract.500 is never accepted
by functional load thresholds. Gateway and database restart faults may interrupt
in-flight requests; original idempotency keys must subsequently settle exactly once.
Gateway forwarding never automatically retries financial requests.

Artifacts include actual k6 metrics,5-second Docker/Prometheus samples, hardware,
script digest, exact committed transaction counts, SQL invariants and actual Kafka
projection reconciliation. Tokens/passwords are passed only through process memory
and environment, never fixture files. k6 summary deliberately omits options/env.
Do not enable HTTP debug output, shell tracing or request dumps. Artifact scans reject
known credentials and JWT-shaped values.

`--faults` only targets verified Compose-labelled synthetic containers: kill a
Gateway/Ledger replica, broker/cache outage, database restart, bounded runtime-role
connection exhaustion, and50ms netem inside a verified Ledger network namespace.
The network helper receives only NET_ADMIN, no host networking or Docker socket.
Recovery restores the same replicas and removes only the qdisc created by this run.
The complete optional monitoring stack is not a prerequisite for these faults.

CI `performance-contracts` compiles scenarios and validates configuration; it is
explicitly not a load/fault execution. Consult the architecture performance report
for actual measured runs, failures, resource settings and limitations.
