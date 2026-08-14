# syntax=docker/dockerfile:1

# ── Build ──────────────────────────────────────────────────────────────────
# Maven and a full JDK are needed to compile and are dead weight at runtime, so
# they stay in a stage that never ships. The final image carries a JRE and a jar.
FROM docker.io/library/maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies resolve from the POM alone, so copying it first means a code-only
# change reuses the cached layer instead of re-downloading the world. -B is
# batch mode: no ANSI progress bars scrolling through the build log.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests are not run here. They need Testcontainers, which needs a container
# runtime the build does not have and should not be given — a build that can
# start containers is a build that can reach the host. They run separately.
RUN mvn -B -q clean package -DskipTests

# ── Runtime ────────────────────────────────────────────────────────────────
FROM docker.io/library/eclipse-temurin:21-jre-noble
WORKDIR /app

# curl is here for the healthcheck below and nothing else. Installed before the
# user is dropped, because that is the last moment root is available.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 1001 app \
 && useradd --system --uid 1001 --gid app --home /app --shell /usr/sbin/nologin app \
 && mkdir -p /app/var/storage \
 && chown -R app:app /app

COPY --from=build --chown=app:app /build/target/*.jar /app/app.jar

# Never root. A remote-code-execution bug in an app running as root is a
# compromised host; the same bug as an unprivileged user is much less.
USER app
EXPOSE 8080

# Container memory is not the host's. Without this the JVM sizes its heap
# against the whole machine, overcommits inside a limited container, and is
# killed by the OOM killer with no Java-side error to explain it.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"

# Asks the app, not the port. A listening socket only says the JVM started;
# this says the datasource answered and Flyway finished, which is what the
# proxy in front actually needs to know before sending traffic.
HEALTHCHECK --interval=15s --timeout=5s --start-period=120s --retries=6 \
  CMD curl -fsS http://localhost:8080/actuator/health | grep -q '"status":"UP"'

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
