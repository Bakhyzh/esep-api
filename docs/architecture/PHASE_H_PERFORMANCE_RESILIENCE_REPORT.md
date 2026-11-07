# Phase H — performance and resilience report

Status: locally implemented and verified; feature CI/main publication pending.
Not a production capacity claim.

## Scope and resource controls

Added nine bounded real k6 workloads, a pinned optional Docker profile, protected
synthetic provisioning, resource sampling, exact financial reconciliation, controlled
fault orchestration and an offline CI contract check. The existing transaction
algorithm, ownership checks, financial schema and monolith remain unchanged.

Apple Silicon arm64 host:8GB RAM,8 logical CPUs; Docker VM4109803520bytes,8CPUs,
shared with unrelated workloads. Host k6v2.3.0, go1.27.1,darwin/arm64; pinned Docker
k6v2.3.0 executed separately. UTC artifact timestamps avoid host JDK timezone drift.
The load phase uses one Ledger and two Gateways. Replica faults temporarily start
Ledger2. Optional Grafana is stopped; Loki/Jaeger/Collector are also paused for the
resource-intensive replica/fault phase. Prometheus and exporters continue sampling.
Keycloak local heap override256MiB preserves its realm, keys, DB and password policy.
No public endpoint, existing data migration, volume deletion or production switch.

## Measured load results

Each run creates eight own accounts funded with exactly800.0000 synthetic KZT.
Opposite-direction transfers use exact decimal strings. Each case validates SQL
invariants, observed201 counts against committed transaction rows, exact aggregate
balances and the actual Kafka-built Analytics manifest under a fixed write fence.
The first batch used Hikari5; the final duplicate/analytics/balancing batch used10.
The local Compose Ledger pool is tuned to10 per instance, within runtime role30.

| Run / case | Exit | Requests | RPS | p50 ms | p95 ms | p99 ms | Non-2xx % | PG connections peak | Kafka lag peak |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 20261107T075445Z-fddb4b1b87b4 / baseline | 0 | 19 | 0.90 | 99.89 | 146.65 | 168.70 | 0.00 | 14 | 1 |
| 20261107T075445Z-fddb4b1b87b4 / normal | 0 | 300 | 9.95 | 101.78 | 275.57 | 2483.04 | 0.00 | 19 | 0 |
| 20261107T075445Z-fddb4b1b87b4 / stress | 0 | 1357 | 38.57 | 89.86 | 371.15 | 612.06 | 0.00 | 21 | 0 |
| 20261107T075445Z-fddb4b1b87b4 / spike | 0 | 761 | 28.13 | 64.08 | 211.50 | 305.38 | 0.00 | 22 | 0 |
| 20261107T075445Z-fddb4b1b87b4 / soak | 0 | 996 | 3.32 | 75.69 | 258.98 | 473.82 | 0.00 | 22 | 0 |
| 20261107T075445Z-fddb4b1b87b4 / transfers | 0 | 100 | 3.32 | 162.12 | 386.43 | 610.46 | 0.00 | 22 | 1 |
| 20261107T075445Z-fddb4b1b87b4 / duplicates | 99 | 180 | 5.77 | 234.36 | 576.26 | 683.40 | 20.00 | 22 | 0 |
| 20261107T081204Z-e934fce080d5 / duplicates | 0 | 180 | 5.52 | 276.07 | 950.75 | 1075.50 | 20.00 | 25 | 0 |
| 20261107T081204Z-e934fce080d5 / analytics | 0 | 380 | 12.65 | 51.42 | 140.45 | 234.26 | 0.00 | 25 | 0 |
| 20261107T081204Z-e934fce080d5 / balancing | 0 | 247 | 12.20 | 44.70 | 224.11 | 654.41 | 0.00 | 25 | 0 |

Non-2xx includes intentional idempotency422 and allowed stress429. Exit99 is a failed threshold, never PASS.

Workload definitions: baseline1VU20s, normal4VU30s, stress1→4→8→0over35s,
spike1→8→1over27s, soak2VU300s, transfers4VU30s, duplicates4VU30s with four
parallel copies per logical request, analytics4VU30s, balancing4VU20s.
The300-second soak executed two real refresh-token operations. RPS counts API
requests; k6 HTTP duration includes the refresh requests where present. Baseline through
transfers completed with zero unexpected responses. The corrected duplicate case
committed36 logical transfers from180 HTTP requests, including36 expected422s.
Analytics and balancing also passed. Both Gateway instance counters were nonzero.

Five-second container samples observed maximum individual-container CPU232.37%
and memory456.5MiB in the first batch (memory peak:Keycloak); the later batch
observed CPU215.34% and memory447.3MiB. These are sampled per-container peaks,
not whole-host utilization or guaranteed maxima. Raw artifacts preserve all samples.
No saturation point was reached; stress38.57RPS is an observed workload, not capacity.
Normal p99 included a2483.04ms tail. No production latency SLO has been established.

## Failures discovered and corrected

1. The first duplicate harness expected409 for a changed payload. The verified
   existing API contract is422 `IDEMPOTENCY_KEY_REUSED`; the harness was corrected,
   preserving business semantics. That first exit99 remains explicitly failed.
2. A later real duplicate run hit Hikari5 starvation:11 connection timeouts and
   CannotCreateTransactionException summaries; requests took~5.68seconds. Financial
   invariants still held, but the run failed. Pool10 rerun passed strict thresholds;
   neither500 acceptance nor money/ownership assertions were relaxed.
3. Forced replica termination may close HTTP before any response. The fault helper
   now records HTTPException as a transport failure, then requires exact original-key
   settlement and conservation. It does not retry forwarding inside Gateway.
4. Keycloak restarted during a full-monitoring/second-Ledger test run on the4GB
   VM, breaking that run; the exact termination cause was not established. Optional tracing backends are paused for fault tests.
5. The connection-budget probe originally slept12seconds despite the existing5s
   restricted-role statement timeout. The probe now uses4seconds and resolves the
   Docker command once before concurrent launch. The timeout remains enforced.

6. Actual Docker startup found an unquoted tmpfs comma parsed as a second mount.
   Quoted the complete mount and added a rendered-configuration assertion; syntax-only
   validation alone had not found this runtime issue.

7. The first container load used Host `edge`, which the explicit local edge host
   allowlist correctly rejected with444/EOF. Docker k6 now sends the verified public
   local Host while resolving the edge privately; the host allowlist stays strict.

## Controlled failures

First successful fault subset, artifact20261107T081642Z-44ee6fffb4a6:
- Gateway kill:50/50 reads200; both Gateway/Ledger pairs subsequently recovered.
- Ledger kill:40 requests over8 keys returned8x201,29x200,3x500; original-key
  recovery produced exactly8 transactions and conserved0.0008.
- Redis outage: Analytics SQL fallback200, financial write503 before forwarding,
  no transaction row and no balance movement for the rejected key.
- Kafka outage: financial commit201, two pending outbox records, no premature
  notification; after restart exactly one notification and correct balances.
- PostgreSQL restart:40 requests returned4x500,8x201,5x503,23x200; all8 original
  keys settled once, exact0.0008 conserved; actual Kafka reconciliation passed.
Final complete fault run20261107T082328Z-cb5e8acd0c73 passed all six groups.
The repeated PostgreSQL restart observed5x500,2x201,33x503 before all8 original
keys settled once; the earlier subset statuses above remain separate evidence.
Runtime connection limit30 admitted25 probe sessions and rejected9; existing pooled
financial request returned201 and settled once. The actual50ms Ledger namespace
egress delay increased request median from52.21ms to366.88ms; after removal the
median recovered to53.38ms. All SQL/Kafka checks passed and only the created qdisc
was removed.
Pinned Docker k6 baseline:17requests,0.847RPS,p50=119.22ms,p95=501.85ms,
p99=891.56ms,zero unexpected responses; exit0, exact balance/SQL/Kafka
reconciliation passed (artifact20261107T082328Z-cb5e8acd0c73).

## Execution and publication

All nine k6 scenarios compile in the pinned Docker image. Optional performance
Compose and Python syntax checks pass. CI contract compilation is not a claim of
executed load. Maven financial regression/CI must remain green before publication.
See tests/performance/README.md for exact commands, containment and credential rules.
Temporary provider identities are removed; synthetic financial history is retained.
Artifacts contain no fixture tokens or passwords; known-secret/JWT scans are required.
Feature commit, CI and main publication are pending at report creation.

## Limits and rollback

This is a shared single-host local laboratory, not HA or production certification.
The five-minute soak does not demonstrate long-term stability. Replica/DB outages
can fail in-flight HTTP requests; clients must reuse their original key. Kafka is
RF1. No production financial data was modified. To roll back the tuning, restore
the previous Compose pool value and recreate only the service during a maintenance
window; do not drop databases or migrations. Stop optional fault/performance tools,
restore any paused telemetry containers and verify readiness/invariants before use.
Phase I remains responsible for broker authentication/ACLs, comprehensive end-to-end
CI, production-oriented configuration, image/security checks and final documentation.

### CI follow-up

Initial feature69cfc70253e6aa2a352a750dbfe489d55d316f7a / run42355620767:
9 of10 jobs passed, including root Maven verify, all five services, performance
contracts and observability. Operations role/encryption tests passed, but the PITR
step failed with a protected generic Docker error; this run was not accepted.
Inspection found a readiness race: Unix-socket pg_isready can accept the image's
initialization-only temporary server. The lab now requires TCP readiness of the
final server and emits safe filename/line diagnostics without SQL or credentials.
The original generic failure's exact cause is unproven. Real isolated PITR rerun
passed:9.255seconds, pg_verifybackup passed, recovered only `keep`, excluded the
post-target transaction; all new lab volumes retained, financial DBs never accessed.
Main integration awaits a complete green follow-up CI run.
