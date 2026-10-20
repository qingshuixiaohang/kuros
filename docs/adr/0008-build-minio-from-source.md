# ADR 0008：从上游源码构建 MinIO 容器

- 状态：Accepted
- 日期：2026-10-20

## 背景

Compose 和 `MinIOStorageStrategyTest` 依赖 `quay.io/minio/minio:latest`。CI 当前拉取该镜像返回 401，导致部署冒烟和后端全量测试失败。MinIO 社区版已转为源码分发，官方仓库归档；官方最近发布说明给出了从源码构建容器的流程。

## 决策

- 保留 MinIO 服务、S3 API、Java MinIO SDK、数据卷、端口和 `MINIO_*` 配置，不迁移到其他对象存储实现。
- 在仓库内维护一个 Dockerfile，从官方 MinIO 源码安装指定版本并构建本地镜像。
- 固定 Go builder 和 Alpine runtime 基础镜像的多架构 manifest digest，避免标签更新改变构建输入。
- Compose 和 Testcontainers 共用这一 Dockerfile，避免开发环境、集成测试和部署冒烟使用不同实现。
- 将 MinIO 限定为当前项目的开发、集成测试和 Compose 验收服务。官方仓库已归档且社区版不再提供维护保证；生产部署需另行评估维护与安全更新来源。

## 后果

- 首次构建需要 Go 基础镜像及 Go 模块下载，耗时高于拉取预构建镜像；Docker build cache 可复用编译层。
- 上游版本必须明确固定，避免 `latest` 造成不可复现构建。
- MinIO 健康检查改为其 HTTP readiness endpoint，不再依赖官方容器内的 `mc` 命令。

## 参考

- [MinIO 官方源码仓库](https://github.com/minio/minio)
- [MinIO 官方 RELEASE.2025-10-15T17-29-55Z 构建说明](https://github.com/minio/minio/releases/tag/RELEASE.2025-10-15T17-29-55Z)
