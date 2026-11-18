# kuros-web 改进计划（基于 2026-10-10 双审计）

> 排序原则：**面试价值 ÷ 修复成本**，兼顾真实风险。
> 安全类最高——面试官 10 秒能复现的漏洞，比任何技术债都致命。
> 每批都遵循项目工作流：TDD 垂直切片 → code-review → commit。

## 已确认的问题分布

| 来源 | 高 | 中 | 低 | 合计 |
|---|---|---|---|---|
| 后端审计 | 6 | 15 | 10 | 31 |
| 前端审计 | 10 | 37 | 5 | 52 |
| 我的独立复查 | — | — | — | 3（与后端审计重叠，已合并） |

前后端审计共 83 条，去重后实际约 75 条独立问题。

---

## Batch A：安全止血（4 项，全部已复现验证）

**为什么最先做**：这几条是面试官能当场复现的漏洞。作品集里有一个未授权访问漏洞，比没有技术亮点更致命。

| # | 问题 | 位置 | 修法 |
|---|---|---|---|
| A1 | dev 固定验证码 123456 是**代码默认值**且默认回显 → 任意手机号可登录 | `kuros-user/application.properties:68,71` | 默认值改空 + `dev-code-exposed` 默认 false；dev profile 才允许便利值 |
| A2 | `/internal/**` 零鉴权 + compose 把 8091 发布到宿主机 → 可批量导出全站用户 | `SaTokenConfigure.java:63`、`compose.yml:263` | 端口绑定 `127.0.0.1`；加 `X-Internal-Token` 共享密钥校验 |
| A3 | `StpUtil.login()` 在 DB 事务内写 Redis 会话 → 回滚产生孤儿会话，且 backend 侧直接放行 | `AuthService.java:106` | 移入 `afterCommit`（项目已有这个纪律，只是漏了这处） |
| A4 | 举报删帖不驱逐 `postDetail` 缓存 → 违规内容最长 60s 仍可读 | `ContentReportService.java:114-124` | 补 `twoLevelCache.evict` |

**验收**：A1 需测试"未配 env 时登录返回 4xx 而非 session"；A2 需测试"无 token 访问 `/internal/**` 返回 401"；A3 需测试"建号失败时不产生 Redis 会话"；A4 需测试"举报处置后详情 404"。

---

## Batch B：缓存与并发一致性（3 项，技术含量最高）

**为什么第二**：这是项目主打难点，也是面试官深挖的主战场。这几条修完，"Redis 与 DB 一致性"这个追问就有完整答案了。

| # | 问题 | 位置 | 修法 |
|---|---|---|---|
| B1 | 缓存驱逐/回填写在事务边界内，违反项目自己的 `afterCommit` 纪律 → 回滚残留幽灵数据 | `PostPublishingService.java:108-109` 等 5 处 | 抽 `CacheInvalidationAfterCommit` 工具，统一移入 afterCommit |
| B2 | 分布式锁无续期，TTL 硬 3s → 锁过期后互斥失效，防击穿承诺破裂 | `DistributedLock.java:61-66` | 加 watchdog 续期；`interact` 内把 MQ 投递移出锁外缩短临界区 |
| B3 | 互动计数无对账 + state/count 两 key TTL 独立 → 计数双重计入或静默回退 | `InteractionRedisStore.java:63-105` | state+count 合并同一 hash 或一次 Lua；加对账任务 + DLQ 监控 |

**面试价值**：B1/B2/B3 每一条都能讲"遇到什么问题→原方案为何不行→怎么修→收益是什么"，正好是项目要求的 STAR 叙事。

---

## Batch C：读路径加固（3 项，有量化空间）

| # | 问题 | 位置 | 修法 |
|---|---|---|---|
| C1 | 帖子列表 DB 层 N+1：一页 50 条 = 101 条 SQL | `CommunityPostService.java:295,330-345` | 改批量 `findByPostIdIn` + 内存分组 |
| C2 | `comments.author_id` 无索引 → 个人中心评论列表全表扫 | `V4__comments.sql:16-17` | 加迁移建复合索引 |
| C3 | backend 的 `/actuator/prometheus` 不在白名单 → 指标采不动 | `SaTokenConfigure.java:57-67` | 与 user 服务对齐，补白名单 |

**面试价值**：C1 修完能给出真实的前后 SQL 数对比（101 → 3），这是压测叙事里最硬的数字。

---

## Batch D：测试补强（3 项，把恒绿测试变真）

| # | 问题 | 位置 | 修法 |
|---|---|---|---|
| D1 | 429 限流测试**结构上恒绿**（阈值 100 只发 20 请求，不触发就不断言） | `RateLimitIntegrationTest.java:78-102` | 阈值降到 1~2，让 429 必然发生 |
| D2 | 删帖过滤测试**零断言** | `FeedTimelineIntegrationTest.java:172-193` | 补 `isEmpty()` 断言；顺带修 `findPublishedByIds` 不过滤 DELETED |
| D3 | `FeedFanoutListener`（#12 核心写路径）零覆盖 | `feed/event/FeedFanoutListener.java` | 补集成测试：AFTER_COMMIT 时序 / 排除作者 / 降级 |

**面试价值**：D1/D2 是"发现自己的测试是假的"——这个故事本身就能讲，比多一个绿色勾更有说服力。

---

## Batch E：前端重构（按依赖顺序）

| # | 问题 | 位置 |
|---|---|---|
| E1 | 引入 mutation 层：全仓 0 处 `useMutation`/`invalidateQueries`，写后读一致性无防线 | 全仓 |
| E2 | 首页 Feed N+1：20 帖 = 21 次请求 | `community-home.tsx:80-89` |
| E3 | 6 个页面仍是纯 mock 主数据源，无三态 | characters/echoes/tools/news/creations |
| E4 | `/search` 与 `/?tab=following` 零 e2e 覆盖 | 无 spec |
| E5 | `community-home.tsx` 264 行承载 9 个关注点 | 拆组件 |

---

## Batch F：架构与卫生（长期，可选）

- M14 `post` ↔ `interaction` 双向依赖、`report` 域直接写 post/comment 实体
- M15 跨服务复制代码行为漂移（`StpInterfaceImpl` 两份行为相反）
- M1 无通用异常处理器 + `ResourceNotFoundException` 错误码张冠李戴
- L1 Flyway V8 跳号未记录
- M11 无 tracing、无 logback、`log.error` 0 次

---

## 执行方式

每批内部按 TDD 垂直切片走：先写失败测试 → 最小实现 → code-review → commit。
耗时验证（全量 mvn test、Playwright、compose 冒烟）按项目分工由用户执行。
