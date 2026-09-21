FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /workspace
COPY . .
# BuildKit 缓存挂载：与 kuros-backend 同款（首次下载全量依赖，之后 pom 不变即秒级复用）
RUN --mount=type=cache,target=/root/.m2 chmod +x mvnw && ./mvnw -DskipTests package

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10002 --no-create-home kuros
COPY --from=build /workspace/target/kuros-gateway-0.0.1-SNAPSHOT.jar /app/app.jar
RUN chown -R kuros:kuros /app

USER kuros
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
