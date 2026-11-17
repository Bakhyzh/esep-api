# Phase I — hardening and integrated verification

Status: source published as ffbdc6ae53cc483bcf4ceef55bc3e9885567c771;
CI44126587328 passed all12 jobs. Main workflow44128575834 passed all12
verification jobs and the GHCR image job; server deployment was skipped. The
remote SHA was verified. No production deployment.

Implemented authenticated Kafka principals and topic/group ACLs, fail-closed storage
initialization, offline encrypted broker preservation, clone-based upgrade/rollback,
production startup guards and HTTPS templates, dedicated migration entry points,
pinned images, complete-repository secret scanning, runtime vulnerability scanning,
actual event-schema checks and complete-stack browser CI. Publication of service
images requires explicit opt-in; automatic production rollout remains disabled.

Security backports retain Boot4.1.1/Cloud2025.1.4: Tomcat11.0.27, Jackson3.1.7/
2.21.7, lz4-java1.11.4 and snappy-java1.1.10.9. Four critical embedded-Tomcat
findings in the initial Ledger scan were fixed. All five rebuilt service images
subsequently reported0 critical/0 high (scan evidence dated2026-11-07).
The supported Kafka4.3.2 image includes pinned Alpine security package fixes;
its actual scan reported0 critical/0 high, one medium. Native LZ4/Snappy payload
round trips were tested against a real compatibility broker.

The old Kafka3.9.1 image reported7 critical/97 high. Original writable-layer logs
were preserved into the named volume only while fenced, with encrypted restore
verification and472 matching files. A later offline clone contained465 files.
Actual candidate and rollback tests preserved36 business partition offsets,
18 numeric committed group positions and metadata3.9-IV0; role-forgery probes
were denied in both versions. The local cutover preserved the same state. No
metadata feature upgrade was finalized. Historical Phase H benchmarks precede
this security upgrade; no improved throughput is inferred.

Keycloak26.7.0 reported6 critical;26.7.5 retained one critical OS finding.
Candidate26.8.2 reported0 critical/4 high. The isolated restored identity clone
passed actual RS256/PKCE, exact clients/audience/roles, issuer/subject, signing-key
and existing identity/credential checks. Its original database is retained.
The first final Compose startup ended143; on2026-11-17 the selected clone started
and the full real browser/API E2E passed. Five-current-database encrypted backup
and row/sequence-exact isolated restore verification also passed. Remaining provider findings
require production remediation/review; they are not waived by local operation.

Previous complete patched Maven verification: User23, Ledger115, Notification27,
Analytics34, Gateway18, DBA4; monolith248 with one opt-in live skip. All passed.
Earlier local contracts:500 identity,199 transfer,51 account and250 posting payloads
validated without logging contents. Real isolated TLS1.2/TLS1.3, untrusted-CA
rejection and Host rejection passed. Dedicated migration commands executed for
all four business databases without starting runtime services/consumers.

Pitfalls fixed during testing included a production configuration/bean name
collision, strict DB URL option validation, an empty-default-schema restore
conflict and operator-subject comparison using the OIDC ID token. Assertions were
strengthened; signature, audience, ownership and financial checks were not weakened.

Final executed E2E, scan date/findings, CI receipt, source SHA and push status are
recorded in docs/FINAL_ESEP_IMPLEMENTATION_REPORT.md. Existing original worktrees,
db-lab and seven original architecture documents are preserved separately.

Final hardening includes a same-engine PostgreSQL17.11 derivative with gosu1.20,
Go1.27.4/x/sys0.44.0 and fixed krb5/libxslt packages. Actual scan: zero findings
at every severity; five-database row/sequence fingerprints survived replacement.
Local complete API/browser/broker-recovery E2E passed again after the fixes.
The clean Linux CI also passed the full real PKCE/browser, identity, transfer,
notification, analytics, outage/recovery, event contracts and broker recreation
workflow. It preserved36 business partition offsets and15 committed group positions
in that fresh fixture; the populated local fixture preserved18 committed positions.

Refreshed scans report six provider HIGH and three Nginx HIGH findings, plus an
end-of-support local Redis OS. These remain production gates, not suppressed
findings. Earlier CI failures and their fixes are described in the final report.
