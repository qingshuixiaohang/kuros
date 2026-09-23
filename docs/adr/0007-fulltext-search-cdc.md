# 0007. 全文检索 + CDC 增量索引：Elasticsearch ik 分词 + Canal 订阅 binlog

日期：2026-09-23

状态：已接受

## 背景与问题

#13 加固了读路径（两级缓存 + 游标分页），但**搜索**仍是 MySQL `LIKE '%keyword%'` 的模糊匹配（`CommunityPostRepository.findVisiblePosts`），暴露三重结构性退化（本项目无生产流量，为结构性论证，非事故复盘）：

1. **索引失效全表扫**：前导通配符 `%keyword%` 令 B+ 树索引无法使用，查询复杂度 O(n)，数据量越大越慢；且 `findLatestByCursor`/`findHotByCursor` 也带着同一 LIKE 谓词——#13 游标分页省下的深翻成本，被搜索的全表扫吃掉。
2. **无中文分词**：`LIKE` 是子串匹配不是词匹配——"鸣潮攻略"作为整体子串无法命中"鸣潮的攻略"（中间隔字），也无法按词频/相关性排序；中文搜索体验的核心是分词，`LIKE` 根本不具备。
3. **搜索维度残缺**：只搜 title + excerpt，**搜不到正文 content**；无相关性打分（relevance scoring）、无高亮、无字段加权。

引入 ES 又带来第二个矛盾：**MySQL 是权威数据源、ES 是异构只读副本，两者如何保持一致**？帖子发布/编辑/删除、标签变更都要反映到索引。应用层双写（发帖时同步写 ES）会漏捕获非应用来源的 DB 变更（DBA 手工改库、批处理、数据迁移），且双写一致性难保证（ES 写失败如何与 MySQL 事务原子）。

**为什么此刻做**：#11 写路径异步化、#12 Feed 流、#13 读路径加固已构成"高并发内容社区"的读写闭环；搜索是社区另一个最高频入口（找攻略/找内容），也是小哈书专栏核心章节。把搜索抗住（分词 + 相关性 + 深翻不退化）并用 CDC 解决异构数据一致性，与前三片形成完整叙事，是面试可深挖 30 分钟的载体。

## 决策

搜索能力升级为 **Elasticsearch（ik 中文分词）全文检索 + Canal 订阅 binlog 的 CDC 增量索引**，两者咬合：

1. **ES 全文检索**：帖子索引进 ES，用 ik 分词器（建索引 `ik_max_word` 细粒度切全、搜索 `ik_smart` 粗粒度）；多字段 `match` + boost（title^3 > excerpt^2 > content^1）；支持高亮、category/tag/type 过滤、相关性(_score)/时间/热度三种排序。
2. **CDC 增量索引（Canal → RocketMQ → consumer → ES）**：Canal 伪装 MySQL slave 订阅 binlog，`serverMode=rocketMQ` + `flatMessage=true` 把变更投递到 RocketMQ（复用 #11 的 namesrv/broker），backend 内消费者写 ES。以 binlog 为**唯一事实源**——无论变更来自应用、手工改库还是批处理都能捕获；对业务代码**零侵入**；MQ 削峰解耦 + 复用 #11 的 orderly/重试/死信。
3. **⭐消费端"回源组装"**：Canal FlatMessage 的 `posts` 行只含 posts 表列（值全为 String），但 ES 文档需要 tags（在 `post_tags` join 表）和 authorName（在 kuros_user 跨库）。故消费者**不直接用 binlog data 拼文档**，而是拿到变更的 postId → 回查 DB（JPA 取 posts + tags）+ Feign 查 authorName → 组装完整文档 → 按 `_id=postId` upsert。因此 Canal 订阅 `posts` + `post_tags` 两表，标签变更也触发对应 postId 回源重组。这是本切片最关键的正确性约束。
4. **⭐删除语义 = 逻辑删 + 搜索过滤**：`CommunityPost` 是逻辑删除（status→DELETED，非物理 DELETE），binlog 里是 UPDATE。ES 文档保留 `status` 字段，**搜索时 filter `status=PUBLISHED`**——status→DELETED 经 CDC 同步后文档自动被过滤，不物理删 ES 文档。理由：与 CONTEXT「已删除帖子可恢复（内容处置）」语义一致，恢复只需 status 改回、索引自动重新可见，比删文档简单可逆。物理 DELETE 事件（如清理任务）消费端兜底 delete by _id。
5. **计数与索引解耦（呼应 #13）**：ES 文档冗余计数字段（like/comment/view）用于热度排序，但那是 CDC 同步的 DB 冗余列快照、允许短暂滞后（热度排序非精确实时）；搜索结果的**实时计数**仍可在组装时从 #11 实时 Redis 源叠加，与 #13 计数解耦一致。
6. **index alias + 全量重建**：真实索引 `post_search_v1`，对外别名 `post_search`。全量重建 = 遍历 posts bulk 写入 `post_search_v2` → 原子切别名 → 删旧索引，**mapping 变更零停机**；首次部署跑一次全量初始化（存量帖子进 ES）。
7. **search_after 游标分页**：搜索深翻用 ES `search_after`（上一页最后一条的 sort values 作游标），替代 from-size（深翻要协调所有分片重排前 from 条再丢弃，O(from) 退化，与 #13 拒 offset 同理）；响应复用 #13 `CursorPageResult` 外层契约（items + nextCursor + hasMore，不带 total），nextCursor 编码 search_after sort values。
8. **技术栈基线**：Spring Boot 4.1.1 → **Spring Data Elasticsearch 6.1.x**（BOM 托管）为主 + 原生 ES Java Client 补充；ES 服务端 **9.4.5** + **analysis-ik 9.4.5**（严格等版本，自建带 ik 的 Dockerfile）；**Canal 1.1.8**（支持 MySQL 8 `caching_sha2_password`）。ES 客户端默认 Jackson 2 mapper，与项目已有 Jackson 2（sa-token）共存无冲突。
9. **部署 + 降级**：compose 加 ES（单机、关闭 x-pack security、堆收敛 512m 防 OOM）+ canal-server + Kibana（观测面，CI 不启）；拒 canal-admin（单机演示不需要）。MySQL 补 binlog 参数（`--server-id=1 --log-bin --binlog-format=ROW --binlog-row-image=FULL`）+ canal 复制账号（`SELECT, REPLICATION SLAVE, REPLICATION CLIENT`）。**ES 对 backend 软依赖（required:false）**：ES 挂了 `/api/v1/search` 明确返回"搜索暂不可用"（不静默空、不 500），主链路（发帖/读帖/Feed/详情走 MySQL+Redis）零影响；CDC 链路任一环挂只导致索引停更，恢复后 Canal 从 binlog position 续传补齐。

## 备选方案

- **应用层双写（替代 Canal CDC）**：发帖/改/删时同步或异步写 ES。优点：无需 Canal/binlog，实现直观。缺点：① 漏捕获非应用来源的 DB 变更（手工改库/批处理/迁移）；② 双写一致性难（ES 写失败与 MySQL 事务无法原子）；③ 侵入业务代码。拒。
- **应用层发 RocketMQ 业务事件（替代 Canal）**：复用 #12 `PostPublishedEvent` 模式，发帖发事件→消费者写 ES。优点：复用现有事件基建。缺点：同双写的漏捕获问题（删除/改库/其他写入源不发事件）。拒。
- **Canal → backend canal-client 直连（替代经 RocketMQ）**：backend 内嵌 canal-client 直接消费。优点：少一跳。缺点：backend 与 canal 强耦合、无 MQ 削峰/重试/死信、多实例消费协调复杂。拒（经 RocketMQ 复用 #11 基建更稳）。
- **消费端直接用 binlog data 拼文档（替代回源组装）**：优点：不回查 DB，省一次查询。缺点：FlatMessage 的 posts 行没有 tags（join 表）和 authorName（跨库），且值全为 String 需按 sqlType 转 LocalDateTime/long，易错。拒（回源组装虽多一次 DB 读，但 CDC 异步链路可接受，且天然解决 tags/authorName 来源）。
- **from-size 分页（替代 search_after）**：优点：支持跳页。缺点：深翻 O(from) 退化（与 #13 拒 offset 同理）；搜索是"下一页"语义，不需跳页。拒。
- **见 status=DELETED 就删 ES 文档（替代保留+过滤）**：优点：索引更小。缺点：帖子恢复（内容处置可逆）时要重新索引，删除/恢复的 CDC 时序更复杂。拒（保留 status + filter 更简单可逆）。
- **索引评论 Comment（扩大范围）**：拒。评论搜索非高频热点，扩范围稀释"帖子搜索 + CDC"主叙事（对齐 #13 只做两个热点的克制）。留下一片。
- **纯原生 ES Java Client（替代 Spring Data ES）**：优点：API 全、无抽象层。缺点：Boot 4 已从 `RestClient` 迁 `Rest5Client`，手动装配 mapper/client 易踩版本细节、开发效率低。拒为主力，仅作高级 API 补充。

## 影响

- **前端**：社区搜索框在 keyword 非空时切到 `/api/v1/search`，结果渲染 `<mark>` 高亮 + 复用 #13 无限滚动（search_after）；不重做 UI、不做独立搜索页。
- **API 契约**：新增 `/api/v1/search`（ES 驱动，返回 `CursorPageResult<PostSearchItem>`，item 带 highlight 片段）；`/api/v1/posts` 的 LIKE keyword 保留不动（轻量筛选，不破坏 #13 游标契约）。
- **搜索架构**：从 MySQL LIKE 升级为 ES 倒排索引 + ik 分词；新增 Canal CDC 数据管道（binlog → RocketMQ → consumer → ES）+ index alias 零停机重建 + 全量初始化端点。
- **一致性模型**：MySQL 权威、ES 最终一致（binlog→Canal→MQ→ES 秒级延迟窗口）；幂等靠 `_id=postId` upsert；顺序靠 RocketMQ `partitionHash` by postId orderly；删除靠 status 过滤（可逆）。
- **测试**：新增 ES 集成测试（Testcontainers `ElasticsearchContainer` + 自建 ik 镜像：分词命中/多字段/高亮/过滤/排序/search_after）、CDC 消费端组装测试、compose 端到端冒烟（发帖→可搜→延迟→改删同步）、perf 门控基准（LIKE vs ES 耗时）。
- **运维面**：新增 ES（内存大户，堆需收敛）+ Canal + Kibana 容器；MySQL 需开 binlog（ROW）+ canal 复制账号；RocketMQ 新增 CDC topic。
- **已知限制**：① DB↔ES 最终一致，秒级延迟窗口内新帖搜不到（社区场景可接受）；② ES/CDC 链路挂掉期间索引停更，恢复后从 binlog position 续传（binlog 保留期内不丢）；③ authorName 冗余进索引，用户改名需 CDC 捕获 user 库变更或 TTL 兜底（改名低频）；④ 热度排序用的计数是 CDC 快照、非 #11 实时值（允许滞后）；⑤ Canal 长时间宕机且 binlog 已过期（`binlog_expire_logs_seconds`）的变更会丢失，需全量重建兜底。
- **收益验证方式（可复现，禁止编造并发数字）**：① 功能——中文分词命中（"鸣潮攻略"命中"鸣潮的攻略"）、LIKE 搜不到的 ES 能搜到、高亮/过滤/排序/search_after 深翻；② CDC 端到端——发帖到可搜的延迟实测、改删同步；③ 性能——`LIKE '%x%'` vs ES 查询在 N 条帖子下的耗时对比（真实测量）；④ 索引吞吐/全量重建耗时。性能数字以实测为准，无压测数据则只做结构性论证。

## 对应小哈书章节

- Elasticsearch 全文检索（ik 分词、倒排索引、match/highlight/aggregation）、Canal 订阅 binlog 做数据同步（CDC）、异构数据源最终一致、search_after 深分页优化。

## 简历产出

> 为高并发内容社区做全文检索与异构数据同步：① 用 Elasticsearch + ik 中文分词替代 MySQL `LIKE '%x%'`，解决前导通配符全表扫、无分词、无相关性排序三重退化，支持多字段加权匹配、高亮、按相关性/时间/热度排序、search_after 深翻不退化；② 用 Canal 伪装 slave 订阅 MySQL binlog，经 RocketMQ 投递到消费者做 CDC 增量索引——以 binlog 为唯一事实源捕获任意来源的 DB 变更、对业务零侵入，复用异步消息基建的顺序/重试/死信；③ 关键设计——消费端"回源组装"（binlog 只有单表行，回查 DB + Feign 补齐 join 的 tags 与跨库 authorName 再 upsert）、ES `_id=postId` 保幂等、逻辑删除靠 status 过滤保可逆、index alias 实现 mapping 变更零停机重建；④ ES 作为软依赖，不可用时搜索优雅降级、主链路零影响，CDC 断连恢复后从 binlog position 续传。
