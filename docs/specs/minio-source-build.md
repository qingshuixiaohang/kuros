# MinIO 源码构建镜像规格

关联 ADR：`docs/adr/0008-build-minio-from-source.md`

## 问题

当前 CI 从 `quay.io/minio/minio:latest` 拉取镜像时收到 401。该镜像被 Compose 服务和后端 Testcontainers 集成测试共同使用，导致部署验收与后端全量测试失败。

## 目标

保留 MinIO 和现有 S3 集成，通过仓库内可复现的 Dockerfile 从官方指定源码版本构建镜像。Compose 与真实服务器集成测试必须使用同一镜像构建定义。

## 决策

- 固定上游版本 `RELEASE.2025-10-15T17-29-55Z`，使用 Go 1.24 编译。
- 镜像保留 MinIO 可执行程序、9000 S3 API、9001 Console、`MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` 和 `/data` 数据目录行为。
- Compose 服务使用仓库 Dockerfile 构建；Testcontainers 测试也通过该 Dockerfile 构建，不能再拉取 `quay.io/minio/minio:latest`。
- MinIO Compose 健康检查使用 `/minio/health/ready` HTTP endpoint。
- 不修改存储策略、Java SDK、bucket 行为、公开 API 或已有数据卷。

## 验收

- `docker compose config --quiet` 成功。
- `MinIOStorageStrategyTest` 启动源码构建的 MinIO，并通过上传、读取、覆盖、删除用例。
- 部署 CI 能构建 Compose MinIO 镜像并通过 readiness 检查；后续健康检查、业务和媒体持久化步骤继续执行。
- 全量后端测试与部署 CI 均通过。

## 边界与限制

MinIO 上游仓库已归档。此镜像服务于项目当前开发、集成测试和 Compose 验收；它不构成有持续安全维护承诺的生产对象存储方案。
