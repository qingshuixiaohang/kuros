# Ticket 5: compose.yml + .env 新增 MinIO 服务

## 目标
compose 新增 MinIO 容器，backend 服务新增 MinIO 环境变量。

## 变更
- `compose.yml` 新增 `minio` 服务（image: minio/minio, command: server, healthcheck）
- `compose.yml` 新增 `kuros_minio_data` volume
- `backend` 服务新增 MinIO 环境变量
- `.env.example` 新增 MinIO 配置项
- SaToken 白名单：`/media/**` 已在公开路由中（无需修改）

## 阻塞
- Ticket 3（MinIO 配置属性已定义）

## 验证
- `docker compose config` 无报错
- MinIO 容器 healthy
- backend 能连接 MinIO endpoint
