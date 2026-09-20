FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /workspace
COPY . .
# BuildKit 缓存挂载：把容器内的 Maven 本地仓库（/root/.m2）持久化到宿主机缓存卷。
# 为什么需要：每次重建镜像都是全新 Linux 环境，不共享宿主机 Windows 的 .m2，
# 没有这个挂载每次都要重新下载全量依赖（5-15 分钟）；有了它只有首次下载，
# 之后只要 pom 不变就秒级复用。注意：缓存卷不在镜像里，不会增大镜像体积
RUN --mount=type=cache,target=/root/.m2 chmod +x mvnw && ./mvnw -DskipTests package

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
