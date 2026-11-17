# syntax=docker/dockerfile:1
FROM golang:1.27.4-alpine@sha256:70bd7ddea8b6cbe5d6943cde279d6b67feb10cc924fb9100d1d8757c1575b280 AS gosu-build
WORKDIR /src
# Keep upstream helper source; backport its x/sys dependency and Go security fixes.
ADD --checksum=sha256:207a2045e890d3ca3326027c5f585733085960b401a49dbc00e3bb628c054cf8 https://codeload.github.com/tianon/gosu/tar.gz/refs/tags/1.20 /tmp/gosu.tar.gz
RUN tar -xzf /tmp/gosu.tar.gz --strip-components=1 \
    && GOTOOLCHAIN=local go get golang.org/x/sys@v0.44.0 \
    && CGO_ENABLED=0 GOTOOLCHAIN=local go build -trimpath -buildvcs=false -o /gosu .

# Keep the verified PostgreSQL17.11 engine, UID, entrypoint and data format.
FROM postgres:17-alpine@sha256:3540c0362c809df084ffac0ae69fe794515f9de92692f02178bcdce0ef0302ac
COPY --from=gosu-build /gosu /usr/local/bin/gosu
ENV GOSU_VERSION=1.20
RUN apk add --upgrade --no-cache krb5-libs=1.22.3-r0 libxslt=1.1.43-r4 \
    && gosu --version \
    && test "$(gosu postgres id -u)" = 70
