# ---- build stage: full JDK + Maven, thrown away after the build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# dependencies first: this layer is cached until pom.xml changes
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# tests need Docker (Testcontainers) and run in CI, not inside the image build
RUN mvn -B -q -DskipTests package && cp target/esep-api-*.jar app.jar

# ---- runtime stage: JRE only, ~4x smaller than the build image ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# never run the app as root inside the container
RUN addgroup -S esep && adduser -S esep -G esep
USER esep

COPY --from=build /build/app.jar app.jar

EXPOSE 8081
# respect the container memory limit instead of the host's RAM
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD wget -qO- http://localhost:8081/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
