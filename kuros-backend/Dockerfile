FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /workspace
COPY . .
RUN chmod +x mvnw && ./mvnw -DskipTests package

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /app/storage \
    && useradd --system --uid 10001 --no-create-home kuros
COPY --from=build /workspace/target/kuros-backend-0.0.1-SNAPSHOT.jar /app/app.jar
RUN chown -R kuros:kuros /app

USER kuros
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
