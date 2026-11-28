# Kuros 架构决策与阅读顺序

浏览器里的 Next.js 前端通过 Gateway 请求用户服务或内容服务。用户服务负责登录、资料与关注，内容服务负责帖子、评论、互动、举报与媒体。两者有各自的 MySQL 库，共用 Redis 会话。内容服务需要用户资料时，通过 OpenFeign 调用用户服务。运行拓扑及端口见 [项目首页](../../README.md#服务拓扑与端口切片-10-微服务拆分后)。

| 顺着哪条链路读 | 架构决策 | 补充入口 |
| --- | --- | --- |
| 从浏览器请求到业务服务 | [0011 Gateway 与 Nacos](0011-gateway-nacos-discovery.md) → [0003 服务拆分](0003-user-service-split.md) | [前端模块索引](../../front/AGENTS.md)、[Compose 服务配置](../../compose.yml) |
| 登录后发帖 | [0001 Cookie 会话](0001-browser-session-cookie.md) → [0002 Sa-Token 与 Redis](0002-distributed-session-satoken-redis.md) | [安全加固复盘](../learning/15-security-hardening-and-test-honesty.md) |
| 上传图片并关联帖子 | [0012 媒体存储和上传一致性](0012-media-storage-and-upload-consistency.md) → [0008 MinIO 镜像构建](0008-build-minio-from-source.md) | [媒体上传一致性复盘](../learning/28-media-upload-consistency.md) |
| 打开列表、关注 Feed 或帖子详情 | [0005 关注 Feed](0005-feed-timeline-zset.md) → [0006 缓存与游标分页](0006-read-path-hardening.md) → [0009 详情读取互动计数](0009-post-interaction-read-boundary.md) | [读路径复盘](../learning/13-read-path-hardening.md) |
| 点赞到最终落库 | [0004 互动写路径](0004-async-interaction-rocketmq.md) | [互动计数校准](../learning/24-periodic-interaction-count-reconciliation.md) |
| 发帖到可搜索 | [0007 全文检索与 CDC](0007-fulltext-search-cdc.md) | [搜索与索引复盘](../learning/14-fulltext-search-cdc.md) |
| 确认举报并处置内容 | [0013 举报处置写边界](0013-report-moderation-write-boundary.md) | [举报处置复盘](../learning/27-report-moderation-boundary.md) |
| 接口报错后定位请求 | [0014 应用层错误契约](0014-application-api-error-contract.md) → [0010 请求 ID](0010-request-id-propagation.md) | [API 契约](../api-contract.md)、[监控指标复盘](../learning/07-actuator-micrometer.md)、[限流复盘](../learning/04-sentinel-rate-limiting.md) |

ADR 保留决策当时的问题、替代方案和代价。读到旧计划或当时的限制时，从关联配置、代码和验证记录确认现状。若是为了继续帖子列表压测，从 0006 开始，分别看列表计数、分页取数和详情缓存三个问题。
