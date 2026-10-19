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
| #10 | [10-service-split.md](./10-service-split.md) | 服务拆分：用户域独立微服务 kuros-user（两阶段渐进拆 + OpenFeign + 跨库一致性降级） |
| #11 | [11-interaction-async-rocketmq.md](./11-interaction-async-rocketmq.md) | 互动写路径异步化：Redis 计数前置 + RocketMQ 顺序消息落库（消除热点行竞争） |
| #12 | [12-feed-timeline.md](./12-feed-timeline.md) | Feed 流：Redis ZSet Timeline + Push-on-publish 关注流（Pipeline 批量扇出） |
| #13 | [13-read-path-hardening.md](./13-read-path-hardening.md) | 读路径加固：两级缓存（L1 Caffeine + L2 Redis）+ 互斥锁防击穿 + 空值哨兵 + pub/sub 跨节点失效 + keyset 游标分页（计数与缓存解耦） |
| #14 | [14-fulltext-search-cdc.md](./14-fulltext-search-cdc.md) | 全文检索 + CDC：Elasticsearch ik 中文分词替代 MySQL LIKE + Canal 订阅 binlog 经 RocketMQ 做增量索引（回源组装/幂等 upsert/逻辑删可逆/index alias 零停机重建/ES 软依赖降级） |
| — | [15-security-hardening-and-test-honesty.md](./15-security-hardening-and-test-honesty.md) | 安全加固 + 测试诚实性 + 读路径性能：改前红改后绿纪律、缓存失效延迟到提交后、锁看门狗续期、计数对账、N+1 消除（102→3 SQL）、索引收益实测（19995→10 行）、把恒绿假测试改成真测试并抓到 3 个被掩盖的真 bug |
| — | [16-frontend-mutation-layer.md](./16-frontend-mutation-layer.md) | 前端 mutation 层全量化（1 处 → 7 处）：六类写操作的私有真相源问题、useState(prop) 不跟随异步变化、本地插入破坏服务端排序、本地改状态撞上服务端过滤、invalidate 的作用域是缓存 key 不是组件、enabled 条件的静默失败 |
| — | [17-test-baseline-cleanup.md](./17-test-baseline-cleanup.md) | 测试基线清洗（e2e 3 红灯 → 0，后端 168 零失败）：环境缺失伪装成测试失败、长期红灯污染回归基线、被两层 mock 夹住掩盖的鉴权白名单遗漏（游客首页 401）、vitest 单测层从零到 39 例、tsc/format:check/vitest 接入 CI |
| — | [18-community-navigation-extraction.md](./18-community-navigation-extraction.md) | 前端社区壳层拆分：把共享侧栏、顶栏、路由选中态与移动抽屉焦点管理移到独立客户端模块；两处消费点保持行为一致 |
| — | [19-community-feed-extraction.md](./19-community-feed-extraction.md) | 前端社区内容流拆分：将 Banner、Feed 查询状态、帖子卡片和媒体渲染迁到独立模块，首页仅负责页面组合 |
