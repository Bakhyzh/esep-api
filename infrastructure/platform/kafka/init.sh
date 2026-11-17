#!/bin/bash
# Idempotent topic/ACL bootstrap for the isolated local cluster; no topic deletion or offset reset.
set -euo pipefail
umask 077
[[ "${KAFKA_ADMIN_PASSWORD:?required}" =~ ^[A-Za-z0-9_-]{40,100}$ ]] || exit 1
cat > /tmp/esep-kafka-admin.properties <<EOF
security.protocol=SASL_PLAINTEXT
sasl.mechanism=PLAIN
sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username="operator" password="$KAFKA_ADMIN_PASSWORD";
request.timeout.ms=3000
default.api.timeout.ms=10000
EOF
export KAFKA_HEAP_OPTS='-Xms32m -Xmx96m'
common=(--bootstrap-server kafka:9092 --command-config /tmp/esep-kafka-admin.properties)
for topic in esep.users.v1 esep.users.v1.ledger.DLT esep.ledger.transfers.v1 esep.ledger.postings.v1 esep.ledger.accounts.v1 esep.notifications.replay.v1 esep.users.v1.notification.DLT esep.ledger.transfers.v1.notification.DLT esep.notifications.replay.v1.notification.DLT esep.users.v1.analytics.DLT esep.ledger.postings.v1.analytics.DLT esep.ledger.accounts.v1.analytics.DLT; do
  /opt/kafka/bin/kafka-topics.sh "${common[@]}" --create --if-not-exists --topic "$topic" --partitions 3 --replication-factor 1 >/dev/null
done
acl() { /opt/kafka/bin/kafka-acls.sh "${common[@]}" --add --allow-principal "User:$1" "${@:2}" >/dev/null; }
write() { local principal="$1"; shift; for topic in "$@"; do acl "$principal" --topic "$topic" --operation Write --operation Describe; done; }
read() { local principal="$1"; shift; for topic in "$@"; do acl "$principal" --topic "$topic" --operation Read --operation Describe; done; }
write user esep.users.v1
write ledger esep.ledger.transfers.v1 esep.ledger.postings.v1 esep.ledger.accounts.v1 esep.users.v1.ledger.DLT
read ledger esep.users.v1
write notification esep.notifications.replay.v1 esep.users.v1.notification.DLT esep.ledger.transfers.v1.notification.DLT esep.notifications.replay.v1.notification.DLT
read notification esep.users.v1 esep.ledger.transfers.v1 esep.notifications.replay.v1
write analytics esep.users.v1.analytics.DLT esep.ledger.postings.v1.analytics.DLT esep.ledger.accounts.v1.analytics.DLT
read analytics esep.users.v1 esep.ledger.postings.v1 esep.ledger.accounts.v1
for principal in user ledger notification analytics; do acl "$principal" --cluster --operation IdempotentWrite; done
acl ledger --group esep-ledger-identities-v1 --operation Read --operation Describe
for group in esep-notification-identities-v1 esep-notifications-v1; do acl notification --group "$group" --operation Read --operation Describe; done
for group in esep-analytics-identities-v1 esep-analytics-postings-v1 esep-analytics-accounts-v1; do acl analytics --group "$group" --operation Read --operation Describe; done
acl monitor --cluster --operation Describe
acl monitor --topic esep. --resource-pattern-type prefixed --operation Describe
acl monitor --group esep- --resource-pattern-type prefixed --operation Describe
echo 'Local broker topics and least-privilege principal ACLs configured; existing records and offsets preserved'
