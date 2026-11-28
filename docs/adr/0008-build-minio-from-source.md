# 0008. 从上游源码构建 MinIO 容器

- 状态：已接受
- 日期：2026-10-20

## 背景

当时 Compose 和 `MinIOStorageStrategyTest` 都要下载同一个 MinIO 镜像。CI 拉取时收到 401，导致测试在业务逻辑开始前就失败。项目改为使用当时官方发布说明提供的方式，从固定版本源码构建镜像。图片上传和存储方式的选型另见 [ADR 0012](0012-media-storage-and-upload-consistency.md)。

## 决策

- 保留 MinIO 服务、S3 API、Java MinIO SDK、数据卷、端口和 `MINIO_*` 配置，不迁移到其他对象存储实现。
- 在仓库内维护一个 Dockerfile，从官方 MinIO 源码安装指定版本并构建本地镜像。
- 固定 Go builder 和 Alpine runtime 基础镜像的多架构 manifest digest，避免标签更新改变构建输入。
- Compose 和 Testcontainers 共用这一 Dockerfile，避免开发环境、集成测试和部署冒烟使用不同实现。
- 这份镜像主要用于项目开发、集成测试和 Compose 验收。生产部署需另行评估所选版本的维护和安全更新来源。

## 后果

- 首次构建需要 Go 基础镜像及 Go 模块下载，耗时高于拉取预构建镜像；Docker build cache 可复用编译层。
- 上游版本必须明确固定，避免 `latest` 造成不可复现构建。
- MinIO 健康检查改为其 HTTP readiness endpoint，不再依赖官方容器内的 `mc` 命令。

## 参考

- [项目的 MinIO Dockerfile](../../docker/minio/Dockerfile)

- [MinIO 官方源码仓库](https://github.com/minio/minio)
- [MinIO 官方 RELEASE.2025-10-15T17-29-55Z 构建说明](https://github.com/minio/minio/releases/tag/RELEASE.2025-10-15T17-29-55Z)
