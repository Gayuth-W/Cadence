# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml .
COPY cadence-core/pom.xml    cadence-core/
COPY cadence-sdk/pom.xml     cadence-sdk/
COPY cadence-service/pom.xml cadence-service/
COPY cadence-demo/pom.xml    cadence-demo/

COPY cadence-core/src cadence-core/src
COPY cadence-sdk/src  cadence-sdk/src
COPY cadence-demo/src cadence-demo/src

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -pl cadence-demo -am clean package -Dmaven.test.skip=true

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S cadence && adduser -S cadence -G cadence
USER cadence
COPY --from=build /build/cadence-demo/target/cadence-demo-1.0.0.jar app.jar
EXPOSE 8081
ENTRYPOINT ["sh", "-c", "exec java -XX:MaxRAMPercentage=75.0 -jar app.jar"]
