#!/bin/bash
set -euo pipefail
umask 077
if [[ ! -f /var/lib/kafka/data/meta.properties && ! -f /var/lib/kafka/data/.esep-fresh-authorized ]]; then
  echo 'Refusing to format unverified Kafka storage. Preserve existing logs or explicitly initialize a NEW synthetic volume.' >&2
  exit 1
fi
# Consume only our fresh-volume authorization after the first successful storage format.
if [[ -f /var/lib/kafka/data/.esep-fresh-authorized ]]; then
  (for attempt in {1..120}; do
    if [[ -f /var/lib/kafka/data/meta.properties ]]; then rm -f /var/lib/kafka/data/.esep-fresh-authorized; exit 0; fi
    sleep 1
  done) &
fi
for name in BROKER ADMIN USER LEDGER NOTIFICATION ANALYTICS MONITOR; do
  key="KAFKA_${name}_PASSWORD"
  value="${!key:?Missing private broker credential}"
  [[ "$value" =~ ^[A-Za-z0-9_-]{40,100}$ ]] || { echo 'Invalid broker credential format' >&2; exit 1; }
done
export KAFKA_LISTENER_NAME_INTERNAL_PLAIN_SASL_JAAS_CONFIG="org.apache.kafka.common.security.plain.PlainLoginModule required username=\"broker\" password=\"$KAFKA_BROKER_PASSWORD\" user_broker=\"$KAFKA_BROKER_PASSWORD\" user_operator=\"$KAFKA_ADMIN_PASSWORD\" user_user=\"$KAFKA_USER_PASSWORD\" user_ledger=\"$KAFKA_LEDGER_PASSWORD\" user_notification=\"$KAFKA_NOTIFICATION_PASSWORD\" user_analytics=\"$KAFKA_ANALYTICS_PASSWORD\" user_monitor=\"$KAFKA_MONITOR_PASSWORD\";"
export KAFKA_LISTENER_NAME_CONTROLLER_PLAIN_SASL_JAAS_CONFIG="org.apache.kafka.common.security.plain.PlainLoginModule required username=\"broker\" password=\"$KAFKA_BROKER_PASSWORD\" user_broker=\"$KAFKA_BROKER_PASSWORD\";"
# Private tmpfs config for health/administration. Never echo credentials or enable shell tracing.
cat > /tmp/esep-kafka-admin.properties <<EOF
security.protocol=SASL_PLAINTEXT
sasl.mechanism=PLAIN
sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username="operator" password="$KAFKA_ADMIN_PASSWORD";
request.timeout.ms=3000
default.api.timeout.ms=5000
EOF
# The image translates KAFKA_* variables into server properties. Do not persist our
# bootstrap-only password variables as unrelated plaintext properties.
unset KAFKA_BROKER_PASSWORD KAFKA_ADMIN_PASSWORD KAFKA_USER_PASSWORD KAFKA_LEDGER_PASSWORD
unset KAFKA_NOTIFICATION_PASSWORD KAFKA_ANALYTICS_PASSWORD KAFKA_MONITOR_PASSWORD
exec /etc/kafka/docker/run
