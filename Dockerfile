FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src src
RUN ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 storage && useradd --uid 10001 --gid 10001 --no-create-home storage \
    && install -d -m 0755 /var/lib/vigo \
    && install -d -o 10001 -g 10001 -m 0700 /var/lib/vigo/storage /app \
    && chown 10001:10001 /var/lib/vigo
WORKDIR /app
COPY --from=build --chown=10001:10001 /build/target/storage-0.0.1-SNAPSHOT.jar app.jar
USER 10001:10001
VOLUME /var/lib/vigo/storage
EXPOSE 8082 9082
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 CMD curl --fail --silent http://localhost:9082/actuator/health/readiness || exit 1
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-Djava.io.tmpdir=/tmp","-jar","/app/app.jar"]
