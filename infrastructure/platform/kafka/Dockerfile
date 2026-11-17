# Maintained Kafka line, with explicit fixes for OS vulnerabilities found by the actual image scan.
FROM apache/kafka:4.3.2@sha256:852a0d49fc4e656b022e7f23785411802c9a088c78a7c5e39f33969de9c227a7
USER root
RUN apk add --upgrade --no-cache brotli-libs=1.2.1-r0 libexpat=2.10.0-r0 musl=1.2.6-r3 musl-utils=1.2.6-r3
USER appuser
