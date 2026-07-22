# syntax=docker/dockerfile:1
#
# ---- build ----------------------------------------------------------------
# The whole reactor is copied because cadence-service depends on cadence-core.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# All four POMs, because the parent's <modules> list is parsed even when only two are built.
COPY pom.xml .
COPY cadence-core/pom.xml    cadence-core/
COPY cadence-sdk/pom.xml     cadence-sdk/
COPY cadence-service/pom.xml cadence-service/
COPY cadence-demo/pom.xml    cadence-demo/

COPY cadence-core/src    cadence-core/src
COPY cadence-service/src cadence-service/src

# `dependency:go-offline` is deliberately NOT used to pre-warm a cache layer. In a multi-module
# reactor it tries to resolve com.cadence:cadence-core from a remote repository rather than from
# the reactor, and fails (MDEP-568). A BuildKit cache mount on ~/.m2 gives the same "don't
# re-download 200MB on every source edit" benefit without the broken plugin goal.
#
# -Dmaven.test.skip=true (not -DskipTests) also skips *compiling* tests, so the image build does
# not need Testcontainers on the classpath. Tests run in CI, not in a release image.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -pl cadence-core,cadence-service -am clean package -Dmaven.test.skip=true

# ---- run ------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Never run as root. A control plane that can force a production rollback is not the place
# to be casual about container privileges.
RUN addgroup -S cadence && adduser -S cadence -G cadence
USER cadence

COPY --from=build /build/cadence-service/target/cadence-service-1.0.0.jar app.jar

EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

# Container-aware heap sizing, and the JVM must exit on OOM rather than limp along
# in a state where it can neither serve flags nor withdraw a bad one.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -XX:+ExitOnOutOfMemoryError -jar app.jar"]
