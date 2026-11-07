# Phase G — observability implementation report

Status: implemented and verified locally with explicit compact monitoring and
separate two-Ledger fault-test modes. Feature CI and main publication pending.
Full seven-app plus dashboard operation on the shared4-GB Docker VM is not supported
by the observed resource budget; it is not claimed as verified.

## Boundary and implementation

The financial transaction boundary and public APIs are unchanged. Five independent
services expose Micrometer/Prometheus metrics and safe ECS JSON logs. An optional
root Compose override enables official Boot OpenTelemetry tracing/log export on seven
application instances. Pinned native ARM64 images provide Prometheus, Grafana, Loki,
Jaeger v2, Collector Contrib and PostgreSQL/Kafka/Redis exporters. No Docker socket
is mounted. Database monitoring uses platform_ops, never financial writer credentials.

New additive User V2 and Ledger V4 preserve W3C traceparent with committed outbox
rows. Publishers restore context on a later thread, and Kafka observations propagate
it to consumers. This does not alter delivery semantics: at least once, stable event
IDs and idempotent projections remain required. Historical null-context rows remain
valid. Existing monolith V1–V8 and service migration history files are not edited.

Added outbox age/count/availability, Kafka failure/DLT and Analytics hit/miss/error
metrics. URI labels use an explicit contract; user/authorization labels are removed.
Gateway correlation IDs require UUID syntax and untrusted baggage is removed.
Collector allowlists discard identity/SQL/URL/query/error payload attributes and
normalize log bodies/span names. See OBSERVABILITY.md for operational detail.

## Findings addressed during verification

- A generated configuration class initially collided with its @Bean name. Renamed
  the filter bean; no bean overriding was enabled.
- A real SDK test showed legitimate W3C flag bits were rejected by a 00/01-only
  check. Preserve the complete valid flag byte; real cross-thread context restoration
  and scope cleanup now pass.
- Parallel Testcontainers suites on this 8-GB host caused Kafka timeouts. Failed runs
  were not accepted. Full regression is repeated sequentially with bounded heaps.
- The subsequent real broker startup exposed Docker Desktop ETXTBSY copying the
  Testcontainers starter. Test fixtures now interpret the nonempty starter through
  bash rather than execve the open script, retaining the actual broker and original
  RUNNING readiness strategy. No financial assertion or test deadline is disabled.
- Jaeger 2.23 removed legacy /api/services. Smoke checks use /api/v3 and Grafana
  links use Jaeger's actual UI, rather than claiming unverified legacy plugin support.
- Actual Boot metadata identified the retired management.tracing.enabled alias and
  the supported management.tracing.export.enabled replacement. Five new configuration
  tests require explicit exporter opt-in and reject retired properties against the
  dependency metadata; the logging max-batch-size property is checked too.
- On this shared 8-GB M1 /4-GB Docker VM, a cold Ledger startup outlasted its
  prior 60-second health grace; it later became healthy, without OOM or migration
  failure. The optional monitoring override uses a 300-second startup grace while
  retaining real readiness checks. Sequential startup and pausing optional Grafana
  reduce peak memory pressure; this is not a production startup-time claim.
- Raw SQL constraint details and Kafka recovery records can reveal identities or
  idempotency keys. Suppressed those framework logs and retained safe exception-class
  summaries/counters; exported bodies are independently bounded by the Collector.

## Executed verification so far

- Native ARM64 image pulls and digest inspection: eight monitoring images.
- Compose platform plus monitoring syntax validation: pass; no secrets printed.
- Collector/Loki/Jaeger configuration validation: pass.
- Prometheus nine alert rules syntax and synthetic firing-delay unit tests: pass.
- Real SDK context propagation/scope cleanup and malicious metric tag tests: 2 pass.
- Real PostgreSQL/Kafka/Redis exporter targets: UP before intentionally stopping
  optional monitoring for sequential integration tests.
- Final sequential Maven verify after supported-property correction: Ledger 92,
  User 5, Notification 6, Analytics 11, Gateway 8 tests, all pass. Monolith: 248 tests, no failures/errors, one opt-in live
  test skipped; actual provider E2E is separately required below.
- Restricted-role/Flyway regression with the new User V2/Ledger V4: 4 tests pass.
- Five final Docker image builds: pass. Real OTLP privacy canary sent through the
  private Collector and queried from actual Loki/Jaeger storage: pass.
- Complete real Keycloak/API/browser E2E passed, including Kafka stop/recovery,
  exact Analytics backfill/reconciliation, lifecycle replay and replica faults.
  Gateway: 50/50 reads returned200 during the kill. Ledger: 40 requests /8 keys
  returned8x201,27x200,5x500 during the kill; original-key settlement produced
  exactly8 transactions and conserved0.0008. Both pairs recovered and SQL
  financial invariants passed. Transient errors are recorded, not hidden.
- Chromium first-login/PKCE/account/transfer/notification/analytics/logout passed:
  27.2s test /29.4s runner. Temporary provider identities removed; audit data retained.
- Four additional HTTP logging regression tests pass: an exception before container
  error handling must not be recorded as200, and raw paths/correlation input are excluded.
- Memory-adjusted real API/browser E2E passed:20.3s test /23.3s runner.
- Compact real API/browser E2E passed:12.3s test /14.2s runner, all five services
  active, two Gateways and one Ledger. All9 actual scrape targets UP;19 Grafana
  panels provisioned, both data sources healthy;313 returned Loki request streams;
  two continuous money traces through Gateway/Ledger/Analytics/Notification, with
  19 linked spans in the sampled trace. Real log-to-trace links resolved.
- Required real metric series present: HTTP count/histograms, CPU/JVM/Hikari,
  PostgreSQL connections/locks/dead tuples/WAL disk size, Kafka lag, outbox,
  Redis and Analytics cache. Counts are telemetry inventory, not benchmarks.
- Actual retrieved telemetry passed JWT-pattern/private-password/canary checks.
  Real OTLP privacy canary also passed against restored Loki/Jaeger.
- Final authoritative financial SQL invariants passed after memory adjustment.
  Keycloak remained healthy with restart count unchanged at2 during successful reruns.

## Runtime resource findings

With full optional Grafana plus authentication workload, Keycloak was killed and
restarted twice, causing Analytics JWKS retrieval to fail closed. This was not
accepted as an E2E pass. Moving Grafana UI into a separate dashboards profile and
pausing it during tests allowed the complete E2E to pass with no further Keycloak
restarts. Signal collection continues through Prometheus/Loki/Jaeger/Collector.

The following telemetry proof found Ledger replica2 exited with OOM=true/137 at
its384-MiB limit. Measured Ledger Metaspace was about134MiB versus59MiB used heap.
The observability override now provides448MiB with an explicit160-MiB maximum heap
and native headroom; Grafana also has a180-MiB Go soft limit. The adjusted API/browser rerun passed. A later full dashboard attempt still
exceeded the shared VM budget and killed Ledger1; this was not accepted as healthy.
The explicit compact override runs one Ledger and two Gateways with9 actual
scrape targets, and its complete telemetry/API/browser proof passed. Two-Ledger
scaling/fault tests passed separately with Grafana stopped. No ten-target dashboard
PASS is claimed. Full simultaneous scaling/dashboard operation needs additional
measured hardware headroom. No financial data was reset.

The refresh helper initially treated a successful HTTP response string as an object;
JSON parsing was corrected and the complete real refresh/replica/browser flow reran.
Grafana's initial legacy Jaeger data source was replaced by the tested external v2
UI link; provisioning removes only that obsolete locally introduced source.

## Environment recovery during continuation

On continuation the previous ESEP containers, networks and images were absent;
the named financial, Keycloak, Kafka and monitoring volumes and private environment
files were intact. Recreated the same projects without removing any volumes or
reinitializing data. Keycloak explicitly skipped the already-existing esep realm.
The first pinned provider download hit a TLS handshake timeout; a bounded retry
succeeded without disabling TLS. The retained financial database passed the invariant
SQL before new E2E operations. Rebuilt all five apps plus the verified frontend.
BuildKit shares a locked ESEP Maven cache with a bounded 384-MB build heap, reducing
repeated dependency downloads while keeping independent builds/runtime images.

## Rollback and limits

Disable the optional override and recreate app containers from the previous verified
image if needed. Retain data volumes; added nullable trace_parent columns are
backward compatible and must not be dropped as part of rollback. Financial data is
never restored or reset by this change. No production traffic or data is changed.

Local Jaeger uses bounded ephemeral memory (2,000 traces), Loki/Prometheus 24-hour
persistent local retention. Single-host monitoring is not HA, a financial audit store,
a production retention strategy or proof of real customer readiness. Alert rules do
not imply delivered external paging. Production needs authenticated/TLS telemetry,
access controls, durable trace storage, sampling/retention budgets and operational
review. Performance and final hardening phases remain outstanding.

Git commit/push and remote SHA: pending feature CI/publication at report creation.
Previous verified backend main: c53eb8df912d7e3d36b51d67e3d46e3482909ec5;
frontend: a70afab85289b299fe49f481fc909b999d25faf6. No production deployment.
