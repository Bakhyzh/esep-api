# Local security cutover and rollback

These are explicit maintenance procedures for the labelled local synthetic projects,
not production migration commands. The prepared environment has already completed
its broker preservation and identity-clone cutover. Do not repeat first-time steps
on populated volumes. Keep original private env files, encrypted backups and age key.
Never run down-v, a feature-level upgrade, a schema downgrade or two financial writers.

## Ordinary startup

From a new checkout, follow README init/build/start in order. Existing installations
use their original --identity-dir and retain .env.identity-secure. Build includes
the patched PostgreSQL image before either database container starts. Four business
DBs and the identity provider retain their named volumes. Production uses separate
external roles/databases/certificates and is not started by these commands.

## Legacy misplaced broker logs

Only a legacy broker whose KAFKA_LOG_DIRS was not the mounted persistent path needs
this procedure. Stop its isolated User/Ledger/Ledger2/Analytics/Notification and
broker containers first; preserve their private configuration. The one-time helper
expects the original labelled containers, including Ledger2, to exist. A missing
container causes a closed failure and requires scope inspection, not creation of a
second financial writer. The destination volume must be empty.

```sh
python3 infrastructure/platform/kafka/preserve-local.py --synthetic-platform
```

It encrypts an offline snapshot, verifies authenticated decryption and compares
every copied file. It refuses to merge into populated broker storage. Existing
source and volumes remain. A newly initialized installation instead uses
platform.py init and its fail-closed fresh-storage marker; never substitute that
marker for missing history in a populated environment.

## Broker security upgrade

Fence all isolated business writers/consumers. Keep the current broker image and
metadata level; do not finalize newer metadata features. The lab starts/stops the
original broker only to capture stable offsets, then exercises an offline clone.

```sh
docker build -t esep-platform-kafka:candidate infrastructure/platform/kafka
python3 infrastructure/security/scan-image.py --image esep-platform-kafka:candidate --output-dir .platform-lab/security-scans
python3 infrastructure/platform/kafka/upgrade-lab.py --synthetic-clone-only
```

Inspect the newly reported private kafka-upgrade artifact and lab-result.json:
candidate, rollback, offsets, committed positions and authorization must all pass.
Then tag that exact scanned candidate as esep-platform-kafka:local and use its
actual artifact path:

```sh
docker tag esep-platform-kafka:candidate esep-platform-kafka:local
python3 infrastructure/platform/kafka/cutover-local.py --verified-lab-result .platform-backups/kafka-upgrade/<actual-artifact>/lab-result.json
```

The helper rejects stale/different candidate and rollback image IDs. Restart the
platform with platform.py and run actual API/browser/contracts/security checks.
For rollback, fence writers again and use the retained, proven source image only
while the metadata level still permits it; verify offsets, groups and ACLs. An old
vulnerable source image is an isolated recovery option, not a public deployment.

## Identity upgrade

For a legacy provider without a verified selection file, fence business services
while keeping the original provider/database initially reachable. Build runtime
images first. Obtain an actual scan of the exact provider image into the private
scan directory, then run the guarded clone laboratory:

```sh
python3 infrastructure/security/scan-image.py --image quay.io/keycloak/keycloak:26.8.2@sha256:3879c623248408de29a5303178b5816c77e8dfef3890c361f8c0bbeaa979ba3b --output-dir .platform-lab/security-scans
python3 infrastructure/platform/identity-upgrade.py --identity-dir /path/to/original/infrastructure/keycloak
python3 scripts/platform.py start --identity-dir /path/to/original/infrastructure/keycloak
```

The lab backs up all five databases, restores into a new provider DB and checks
existing IDs, roles, credential fingerprints, subjects, keys and real PKCE. The
original keycloak_db remains intact; the selection file is created exclusively and
subsequent attempts refuse to overwrite it. Inspect the actual exit/result before
continuing; failures leave the original data fenced and retained.

Rollback requires stopping the new provider, reconciling any newer identity writes,
and explicitly selecting the retained original DB with its original provider
version/configuration. Never point the older provider at the upgraded clone. Keep
the current selection file and backups for audit; do not erase them to bypass a
guard. Financial ownership and business identity mappings must be reconciled before
resuming writes. No email-based linking or BCrypt decryption is part of recovery.

## Database image or migration maintenance

Stop all business writers/consumers and Keycloak before a five-database backup.
Use the backup/restore commands in platform-operations.md and authenticate a restore
in new databases. Compare row/sequence fingerprints before and after a same-engine
image replacement; inspect ICU/libc collation versions as well as PostgreSQL version.
The executed hardening kept PostgreSQL17.11 and identical collation libraries.

Production migrations use the separate migration profile and external migrator env
files, after backup, fencing and review. For example, the prepared one-off command
is docker compose -f compose.production.yaml --profile migration run --rm user-migrate;
repeat for each approved service migrator. It starts no HTTP server or Kafka writer.
Runtime containers disable Flyway and require schema validation. Never repair a
failed migration by dropping a database, editing historical migrations or enabling
runtime schema mutation.
