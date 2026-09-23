# 全文检索 + CDC 增量索引（Elasticsearch ik 分词 + Canal 订阅 binlog）

> 切片 #14 · 关联 [ADR 0007](../adr/0007-fulltext-search-cdc.md) · 承接 #11 写路径异步化、#12 Feed 流、#13 读路径加固

## Problem Statement

#11/#12/#13 已构成"高并发内容社区"的读写闭环，但**搜索**仍停留在 MySQL `LIKE '%keyword%'` 的模糊匹配（`CommunityPostRepository.findVisiblePosts`，且 `findLatestByCursor`/`findHotByCursor` 也带同一谓词），存在三重结构性退化（本项目无生产流量，为结构性论证）：

1. **索引失效全表扫**：前导通配符 `%keyword%` 令 B+ 树索引无法使用，查询 O(n)；#13 游标分页省下的深翻成本被搜索的全表扫吃掉。
2. **无中文分词**：`LIKE` 是子串匹配不是词匹配——"鸣潮攻略"无法命中"鸣潮的攻略"（中间隔字），也无法按词频/相关性排序。中文搜索的核心是分词，`LIKE` 不具备。
3. **搜索维度残缺**：只搜 title + excerpt，**搜不到正文 content**；无相关性打分、无高亮、无字段加权。

引入 ES 又带来第二个矛盾：**MySQL 是权威源、ES 是异构只读副本，如何保持一致**？帖子发布/编辑/删除、标签变更都要反映到索引。应用层双写会漏捕获非应用来源的 DB 变更（手工改库/批处理/迁移），且双写与 MySQL 事务无法原子。

## Solution

搜索升级为 **Elasticsearch（ik 中文分词）全文检索 + Canal 订阅 binlog 的 CDC 增量索引**，两者咬合：

- **ES 全文检索**：帖子索引进 ES，ik 分词（建索引 `ik_max_word`、搜索 `ik_smart`），多字段 match + boost（title>excerpt>content），支持高亮、category/tag/type 过滤、相关性/时间/热度排序、search_after 深翻。
- **CDC 增量索引**：Canal 伪装 slave 订阅 MySQL binlog → RocketMQ（复用 #11）→ backend 消费者写 ES；以 binlog 为唯一事实源、对业务零侵入。
- **消费端回源组装**：binlog 只有 posts 单表行，消费者回查 DB（posts+tags）+ Feign（authorName）组装完整文档再 `_id=postId` 幂等 upsert。
- **删除可逆**：逻辑删（status→DELETED）经 CDC 同步后搜索时 filter `status=PUBLISHED`，不物理删文档。
- **优雅降级**：ES 为 backend 软依赖，不可用时 `/api/v1/search` 明确返回"搜索暂不可用"，主链路（MySQL+Redis）零影响。

## User Stories

1. 作为用户，我想搜"鸣潮攻略"能命中"鸣潮的攻略详解"这类分词后的内容，这样中文搜索才符合直觉，而不像 `LIKE` 那样要求整串子串完全出现。
2. 作为用户，我想搜索能命中帖子正文 content 里的关键词，而不只是标题和摘要，这样深度内容也能被找到。
3. 作为用户，我想搜索结果按相关性排序（标题命中权重高于正文），这样最相关的帖子排在前面。
4. 作为用户，我想搜索结果里命中的关键词被高亮，这样一眼看到为什么这条被搜出来。
5. 作为用户，我想在搜索结果里按内容分类/标签/帖子类型过滤，这样能缩小到我关心的范围。
6. 作为用户，我想搜索结果能按相关性、发布时间、热度三种方式排序，这样不同诉求都能满足。
7. 作为用户，我想搜索结果下拉加载更多时翻页不变慢（search_after），这样翻到很深的结果也和第一页一样快。
8. 作为用户，我想发帖后过一会儿就能搜到自己的帖子，这样内容发布与可搜索之间只有可接受的秒级延迟。
9. 作为用户，我想编辑帖子后搜索到的标题/正文是最新的，这样不会搜到过期内容。
10. 作为用户，我想删除（逻辑删）帖子后它从搜索结果消失，但恢复后又重新可搜，这样内容处置是可逆的。
11. 作为用户，我想给帖子改标签后，按新标签能搜到、按旧标签搜不到，这样标签筛选始终准确。
12. 作为运维者，我想无论帖子变更来自应用、DBA 手工改库还是批处理，索引都能同步（CDC 以 binlog 为源），这样不会有"绕过应用就漏索引"的盲区。
13. 作为运维者，我想 ES 挂掉时搜索优雅降级、而发帖/读帖/Feed/详情完全不受影响，这样搜索是增强能力而非主链路的单点。
14. 作为运维者，我想 CDC 链路（Canal/MQ）断连恢复后能从 binlog position 续传补齐索引，这样短暂故障不丢变更。
15. 作为运维者，我想 mapping 变更后能零停机重建索引（别名原子切换），这样升级索引结构不用停搜索。
16. 作为运维者，我想首次部署能一键把存量帖子全量导入 ES，这样上线即有完整索引。
17. 作为前端开发者，我想搜索端点返回 `nextCursor`/`hasMore`（复用 #13 游标契约）+ 每条结果的高亮片段，这样我能复用无限滚动组件、直接渲染高亮。
18. 作为开发者，我想旧的 `/api/v1/posts?keyword=` 的 LIKE 轻量筛选保持不变，这样本切片只加搜索能力、不破坏 #13 列表游标契约。
19. 作为面试者，我想这套搜索+CDC 能讲清"为什么不用双写""Canal 挂了怎么办""DB↔ES 不一致窗口多大""ik_max_word vs ik_smart""search_after vs from-size""Canal 如何保序"，这样每个决策都能被深挖追问。

## Implementation Decisions

### D1: ES 索引 mapping + ik 分词

- 索引文档 = `PostSearchDoc`，字段：`postId`(=ES `_id`)、`title`(text, `ik_max_word`, boost 3)、`excerpt`(text, `ik_max_word`, boost 2)、`content`(text, `ik_max_word`, boost 1)、`authorId`(keyword)、`authorName`(text+keyword, 冗余)、`category`(keyword)、`type`(keyword, GUIDE|GENERAL)、`status`(keyword)、`tags`(keyword 数组)、`likeCount`/`commentCount`/`viewCount`(long)、`publishedAt`(date)。
- 建索引用 `ik_max_word`（细粒度切全，召回优先），搜索查询用 `ik_smart`（粗粒度，精度优先）——这是 ik 的标准用法。
- Spring Data Elasticsearch `@Document` + `@Field(type=Text, analyzer=ik_max_word, searchAnalyzer=ik_smart)` 声明 mapping；计数字段用于热度排序（CDC 快照，允许滞后，实时值组装时可从 #11 Redis 叠加）。

### D2: CDC 管道（Canal → RocketMQ → consumer → ES）

- Canal `serverMode=rocketMQ` + `flatMessage=true`，投递到 RocketMQ topic（如 `post-cdc`），复用 #11 的 namesrv/broker。
- Canal instance 订阅 `kuros.posts` + `kuros.post_tags` 两表（`filter.regex`）；`partitionHash=kuros\.posts:id` 让同一 postId 落同一队列，保证同帖变更有序。
- backend 内新增 CDC 消费者（Spring Cloud Stream function，复用 #11 binder 配置模式），`orderly` 消费 + 重试 + 死信（`%DLQ%`）。
- 消费者解析 FlatMessage 取变更的 postId 集合（`data[].id` / `pkNames`），对每个 postId 触发 D3 回源组装。`post_tags` 变更消息据其 `post_id` 列同样触发对应帖子重组。

### D3: 消费端回源组装 + 幂等 upsert（关键约束）

- 收到 postId → 回查 DB：JPA 取 `CommunityPost`（含 tags 关联）；Feign 查 `authorName`（复用 `UserDirectoryFacade`）。
- 组装 `PostSearchDoc` → 按 `_id=postId` **upsert**（index 覆盖写，天然幂等，重复消费/重试不产生副本）。
- **不直接用 FlatMessage 的 `data` 拼文档**：binlog 行没有 tags（join 表）和 authorName（跨库），且值全为 String 需转 LocalDateTime/long，易错。回源组装虽多一次 DB 读 + Feign，但 CDC 是异步链路，可接受。
- 回源时帖子已不存在（物理删）或 status=DELETED：见 D4。

### D4: 删除语义（逻辑删 + 搜索过滤，可逆）

- `CommunityPost` 是逻辑删除（status→DELETED），binlog 里是 UPDATE。CDC 同步后 ES 文档 `status` 变 DELETED，**搜索时 filter `status=PUBLISHED`** 自动排除——不物理删 ES 文档。
- 恢复帖子（status 改回 PUBLISHED）经 CDC 同步后文档自动重新可搜，无需重新索引。
- 若出现物理 DELETE 事件（如未来清理任务）：消费端兜底 `delete by _id`；回源时查不到帖子也兜底删除文档。

### D5: index alias + 全量初始化

- 真实索引带版本号（`post_search_v1`），对外别名 `post_search`；所有读写走别名。
- **全量重建**：遍历 posts 表 bulk 写入 `post_search_v{n+1}` → 原子切别名 → 删旧索引，mapping 变更零停机。
- 提供一个内部全量初始化端点/命令（如 `POST /api/v1/internal/search/reindex`，仅管理员/运维），首次部署与索引损坏恢复时跑。

### D6: search_after 游标 + `/api/v1/search` 契约

- 新增 `GET /api/v1/search?keyword=&category=&tag=&type=&sort=&cursor=&limit=`。
- 深翻用 ES `search_after`：cursor 编码上一页最后一条的 sort values（不透明 base64url，呼应 #13 `CursorCodec`）。
- 响应复用 #13 `CursorPageResult<PostSearchItem>`（items + nextCursor + hasMore，**不带 total**）；`PostSearchItem` = 帖子摘要字段 + `highlight`（title/content 高亮片段 map）。
- 排序 `sort=relevance|latest|hot`：relevance 按 `_score`；latest 按 `publishedAt`；hot 按计数字段。非 relevance 排序需附加 tie-breaker（如 `postId`）保证 search_after 全序稳定。
- `/api/v1/posts` 的 LIKE keyword **保留不动**（轻量筛选，不破坏 #13 列表游标契约）。

### D7: 搜索查询（多字段 + 高亮 + 过滤 + 排序）

- 查询用 `multi_match`（title^3, excerpt^2, content^1）+ `ik_smart`；过滤用 `term`(category/type/status=PUBLISHED) + `terms`(tags)；高亮 `highlight` 返回 title/content 片段。
- 可选增强（时间够才做）：`aggregation` 按 category/tag 统计做 facet 侧栏。
- ES 客户端优先 Spring Data Elasticsearch（`ElasticsearchOperations` / `NativeQuery`）；高亮/聚合等 Spring Data 未直接覆盖的用原生 ES Java Client 补充。

### D8: 部署 + 降级（compose）

- compose 新增：**ES 9.4.5**（单机、`xpack.security.enabled=false`、`-Xms512m -Xmx512m` 防 OOM，自建带 ik 的 Dockerfile）、**canal-server v1.1.8**、**Kibana**（观测面，CI 不启，可用 compose profile 隔离）。拒 canal-admin。
- ES 镜像：`docker.elastic.co` 基础镜像 + `elasticsearch-plugin install` ik 9.4.5（严格等版本）；国内拉取用离线包兜底 + 可配镜像源（对齐 `quay.io/minio` 风格）。
- MySQL：command 补 `--server-id=1 --log-bin=mysql-bin --binlog-format=ROW --binlog-row-image=FULL`；`docker/mysql-init` 加 canal 复制账号（`SELECT, REPLICATION SLAVE, REPLICATION CLIENT`）。
- 依赖：canal → mysql(healthy) + rocketmq-broker(healthy)；backend → ES **软依赖**（`required:false`）。
- 降级：`/api/v1/search` 捕获 ES 不可用 → 返回明确的"搜索服务暂不可用"（非静默空、非 500 崩）；主链路与 CDC 断连恢复续传见 ADR 0007 决策 9。

### D9: 技术栈基线 + 首个 spike

- 基线：Spring Boot 4.1.1 → Spring Data Elasticsearch 6.1.x（BOM 托管）+ ES 9.4.5 + analysis-ik 9.4.5 + Canal 1.1.8；ES 客户端默认 Jackson 2 mapper 与项目已有 Jackson 2 共存无冲突。
- **首个 tracer-bullet 是 spike**：验证 ① Boot 4.1.1 下 Spring Data ES 自动装配的 JsonpMapper 能正常 index+search 一条中文文档且 ik 分词生效；② 国内拉 ES 镜像 + ik 插件通畅（离线兜底）；③ RocketMQ 消费端 FlatMessage 反序列化 + String→类型转换；④ Testcontainers ES + 自建 ik 镜像可用 + CI runner 内存。跑通再铺开。

### D10: 前端搜索载体（最小改动）

- 社区搜索框在 keyword 非空时切到 `/api/v1/search`（新增 `searchPosts` API 层，复用 #13 `CursorPageResult` 类型）；结果渲染 `<mark>` 高亮片段 + 复用 #13「加载更多」无限滚动（search_after）。
- **不重做社区 UI、不做独立搜索页**；keyword 为空时行为不变。

## Testing Decisions

好的测试只验证外部行为（搜索命中/高亮/排序/翻页结果、索引文档正确性、API 契约、降级响应），不绑定实现细节（不断言私有方法、不 mock 被测逻辑本身）。沿用项目既有 seam 类型，Canal→MQ 传输层不做单元/集成测试（太重太脆），交端到端冒烟覆盖：

- **S1 搜索读路径（HTTP 端点 seam，最高）**：`@SpringBootTest` + `@AutoConfigureMockMvc` + Testcontainers `ElasticsearchContainer`（自建 ik 镜像）+ H2。验证：ik 分词命中（"鸣潮攻略"命中"鸣潮的攻略"）、多字段 boost 排序、高亮片段、category/tag/type 过滤、三种排序、search_after 深翻正确性、`CursorPageResult` 契约（data.items/nextCursor/hasMore + 不带 meta）、ES 不可用降级响应。Prior art：#13 `CursorHttpContractIntegrationTest`。
- **S2 CDC 索引写入（indexing service seam）**：service 级集成测试，Testcontainers ES + H2。seed DB（posts + post_tags）→ 调 `PostIndexService.index(postId)` → 断言 ES 文档字段（tags 来自 join、authorName 来自 Feign 桩、status、计数）；验证全量重建、status=DELETED 后搜索过滤排除、恢复后重新可搜、重复 index 幂等（不产生副本）。Prior art：#13 `TwoLevelCacheIntegrationTest`。
- **S3 CDC 端到端（compose 冒烟，deployment 级）**：扩展 `scripts/compose-smoke.mjs` 或新增 `search-cdc-smoke.mjs`——发帖 → 轮询 `/api/v1/search` 直到命中 → 记录端到端延迟 → 改帖/删帖验证索引同步。真 Canal→binlog→MQ→consumer→ES 全链路。进 CI deployment job（ES 堆收敛 + Kibana 不启）；若 runner OOM 则降级本地可复现脚本（spike ④ 确认）。Prior art：`scripts/compose-smoke.mjs`。
- **S4 性能（门控基准，禁止编造数字）**：`@EnabledIfSystemProperty(perf=true)` 门控，`LIKE '%x%'` vs ES 查询在 N 条帖子下的耗时对比 + CDC 端到端延迟实测，`[PERF]` 前缀输出真实数据回填 `docs/learning/14`。默认不进 CI。Prior art：#13 `ReadPathPerfBenchmark`。
- **主套件兼容**：新增 ES/CDC 逻辑不破坏现有 83 个测试；ES 相关集成测试用独立 H2 库名 + 每类独立 JVM（沿用 `reuseForks=false`）。

## Out of Scope

- 索引评论 Comment（非高频热点，扩范围稀释主叙事，留下一片）。
- 自动补全（completion suggester）、拼写纠错、同义词词典（非主叙事，留下一片）。
- 聚合 facet 侧栏（列为可选增强，时间不够则不做）。
- 向量/语义搜索、AI 标签推荐（路线图明确最后单独立项，禁止引入 pgvector）。
- 独立搜索页面 / 社区 UI 重做（前端只做搜索框接入 + 高亮 + 无限滚动的最小载体）。
- `/api/v1/posts` 的 LIKE keyword 迁移到 ES（保留不动，二者并存）。
- ES 集群化/分片副本调优（单机演示足够，生产集群留下一片）。
- canal-admin 可视化运维（单机不需要）。
- authorName 改名的一致性强化（本片靠 CDC 捕获 user 库变更或 TTL 兜底，专项强化留下一片）。

## Further Notes

- 搜索与 CDC 在实现上咬合：CDC 的"回源组装"正是搜索索引文档的数据来源，binlog 的 status 变更正是搜索过滤的依据。面试可串成"全文检索 + 异构数据一致性"完整叙事。
- 关键追问点预埋：① 为什么不用应用层双写/业务事件（漏捕获非应用写入 + 双写一致性弱）；② Canal 挂了怎么办（binlog position 续传 + 全量重建兜底）；③ DB↔ES 不一致窗口多大（binlog→Canal→MQ→ES 秒级，最终一致）；④ ik_max_word vs ik_smart（建索引细粒度召回、搜索粗粒度精度）；⑤ search_after vs from-size（深翻 O(from) 退化，呼应 #13 拒 offset）；⑥ Canal 如何保序（partitionHash by postId + RocketMQ orderly）；⑦ 为什么回源组装而不直接用 binlog data（join 表 tags + 跨库 authorName + String 转类型）；⑧ 删除为什么不物理删 ES 文档（逻辑删可逆，合内容处置语义）。
- 复用点：RocketMQ 基建（#11）、`CursorPageResult`/`CursorCodec`（#13）、`UserDirectoryFacade` Feign（split-08）、计数实时源（#11 `InteractionRedisStore`）、前端无限滚动（#13）。
- 本切片完成后，#11（写）+ #12（Feed）+ #13（读）+ #14（搜索）构成"高并发内容社区"的完整读写检索闭环，是简历主叙事。
- **se-01 spike 结论（go/no-go = GO）**：Spring Boot 4.1.1 下 spring-data-elasticsearch 6.1.1 + elasticsearch-java/rest5-client 9.4.5 + ES/analysis-ik 9.4.5 实测可 index+search 中文，ik 分词生效（"鸣潮攻略"命中"鸣潮的攻略详解"），JsonpMapper 对 `LocalDateTime` 序列化回读不失真，Canal FlatMessage 可被 Jackson 2 解析并提取 postId。**无需回退原生 ES Java Client**。踩坑备忘：`NativeQuery` 在 6.1.1 迁至 `org.springframework.data.elasticsearch.client.elc` 包；Testcontainers 等待策略用 `forPort` 而非 `withPort`。详见 docs/tickets/se-01-es-spike.md。
