# ---- build stage: full JDK + Maven, thrown away after the build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
ENV MAVEN_OPTS="-Xmx384m -XX:ActiveProcessorCount=2"

# dependencies first: this layer is cached until pom.xml changes
COPY pom.xml .
RUN --mount=type=cache,id=esep-maven-21,target=/root/.m2,sharing=locked mvn -B -q dependency:go-offline

COPY src ./src
# tests need Docker (Testcontainers) and run in CI, not inside the image build
RUN --mount=type=cache,id=esep-maven-21,target=/root/.m2,sharing=locked mvn -B -q -DskipTests package && cp target/user-service-*.jar app.jar

# ---- runtime stage: JRE only, ~4x smaller than the build image ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# never run the app as root inside the container
RUN addgroup -S esep && adduser -S esep -G esep
USER esep

COPY --from=build /build/app.jar app.jar

EXPOSE 8083
# respect the container memory limit instead of the host's RAM; on OutOfMemoryError exit at once,
# so Docker restarts a clean JVM instead of keeping a half-broken one that still answers health checks
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=5 \
    CMD wget -qO- http://localhost:8083/actuator/health/readiness | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
