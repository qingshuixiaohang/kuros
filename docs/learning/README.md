# 切片学习复盘

每完成一个微服务演进切片后，在此目录下产出一份学习复盘文档，格式为 `{切片编号}-{简短主题}.md`。

## 文档结构（统一模板）

每份复盘包含以下七个部分：

1. **架构迁移全景** — 重构前后的对比，到底干了什么
2. **关键难点解析** — 踩过的坑、事务边界、顺序依赖等
3. **简历 STAR 写法** — 情境/任务/行动/结果的完整叙事
4. **原理详解** — 核心组件的工作机制和流程图
5. **面试八股文整理** — 高频问题 + 结合项目的回答
6. **技术选型对比** — 为什么选 A 不选 B，表格对比
7. **面试叙事模板** — 30 秒电梯版 + 2 分钟详细版

## 已有文档

| 切片 | 文件 | 主题 |
|---|---|---|
| #1 | [01-satoken-redis-rbac.md](./01-satoken-redis-rbac.md) | SaToken + Redis + RBAC 分布式会话鉴权重构 |
| #2 | [02-minio-storage-strategy.md](./02-minio-storage-strategy.md) | MinIO 对象存储 + 策略模式 |
| #3 | [03-caffeine-cache.md](./03-caffeine-cache.md) | Caffeine 本地缓存加速热读路径 |
| #4 | [04-sentinel-rate-limiting.md](./04-sentinel-rate-limiting.md) | Sentinel 基础限流保护 API |
| #5 | [05-redis-distributed-lock.md](./05-redis-distributed-lock.md) | Redis 分布式锁防止并发重复操作 |
| #6 | [06-springdoc-openapi.md](./06-springdoc-openapi.md) | SpringDoc OpenAPI 自动生成交互式 API 文档 |
| #7 | [07-actuator-micrometer.md](./07-actuator-micrometer.md) | Actuator + Micrometer 应用监控 |
| #8 | [08-nacos-discovery-config.md](./08-nacos-discovery-config.md) | Nacos 注册发现 + 配置中心分层迁移与动态刷新 |
| #9 | [09-gateway.md](./09-gateway.md) | Spring Cloud Gateway 统一入口与 lb 服务发现路由 |
