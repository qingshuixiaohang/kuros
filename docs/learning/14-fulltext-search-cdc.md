# 切片 #14：全文检索 + CDC 增量索引——Elasticsearch ik 分词 + Canal 订阅 binlog

> 承接 #11（互动写路径异步化）、#12（Feed ZSet Timeline）、#13（读路径加固），本切片补上「高并发内容社区」
> 叙事的最后一块——**搜索**。两个咬合的结构性难点：① MySQL `LIKE '%x%'` 的三重退化（前导通配符全表扫、
> 无中文分词、搜索维度残缺）→ Elasticsearch ik 全文检索；② MySQL 权威源与 ES 异构副本的一致性 →
> Canal 伪装 slave 订阅 binlog 经 RocketMQ 做 CDC 增量索引。共 8 个工单（se-01~08），本文是收口复盘。
>
> **纪律声明**：本文所有性能/延迟数字均来自实测——CDC 延迟来自 `scripts/search-cdc-smoke.mjs` 全栈冒烟，
> perf 对比来自 `SearchPerfBenchmark`（前缀 `[PERF]`），**严禁编造 TPS/RT/并发数字**（CONTEXT.md 方向纠偏）。

## 1. 架构迁移全景

### 改造前（切片 #13 完成时）

搜索仍是 MySQL `LIKE '%keyword%'` 模糊匹配（`CommunityPostRepository.findVisiblePosts`，且
`findLatestByCursor`/`findHotByCursor` 也带同一谓词），暴露三重结构性退化（本项目无生产流量，为结构性论证）：

1. **索引失效全表扫**：前导通配符 `%keyword%` 令 B+ 树索引无法使用，查询 O(n)；#13 游标分页省下的深翻成本，被搜索的全表扫吃掉。
2. **无中文分词**：`LIKE` 是子串匹配不是词匹配——「鸣潮攻略」无法命中「鸣潮的攻略」（中间隔字），也无法按词频/相关性排序。中文搜索的核心是分词，`LIKE` 不具备。
3. **搜索维度残缺**：只搜 title + excerpt，**搜不到正文 content**；无相关性打分、无高亮、无字段加权。

### 改造后

- **ES 全文检索读路径**：帖子索引进 ES（`PostSearchDoc`，`_id=postId`），ik 分词（建索引 `ik_max_word` 细粒度召回、搜索 `ik_smart` 粗粒度精度）；`multi_match`（title^3 > excerpt^2 > content^1）+ term/terms 过滤（category/type/status=PUBLISHED/tags）+ highlight 高亮 + 三排序（relevance `_score` / latest `publishedAt` / hot 计数）+ `search_after` 深翻。
- **CDC 增量索引管道**：MySQL binlog（ROW/FULL）→ Canal 伪装 slave（`serverMode=rocketMQ` + `flatMessage=true` + `partitionHash=kuros\.posts:id` 保序）→ RocketMQ topic `kuros-post-cdc`（复用 #11 namesrv/broker）→ backend Spring Cloud Stream `orderly` 消费者 → `PostCdcHandler` → `PostIndexService`。以 binlog 为唯一事实源，对业务零侵入。
- **⭐消费端回源组装**（本切片最关键正确性约束）：binlog 的 posts 行只含单表列（值全为 String），但 ES 文档需要 tags（在 `post_tags` join 表）+ authorName（在 kuros_user 跨库）。故消费者**不直接用 binlog data 拼文档**，而是取变更的 postId → 回查 DB（JPA posts + tags）+ Feign（authorName）→ 组装完整文档 → 按 `_id=postId` upsert（天然幂等）。
- **删除可逆**：`CommunityPost` 逻辑删（status→DELETED，binlog 里是 UPDATE），搜索时 filter `status=PUBLISHED` 自动排除，**不物理删 ES 文档**；恢复 PUBLISHED 经 CDC 同步后自动重新可搜。
- **index alias 零停机重建**：真实索引 `post_search_v1`，对外别名 `post_search`；全量重建 = bulk 写 `v{n+1}` → 原子切别名 → 删旧索引。别名创建有**两条幂等软失败触发路径**：① 启动引导 `SearchIndexBootstrap`@`ApplicationReadyEvent`；② 首次写入兜底 `PostIndexService#index`。
- **ES 软依赖降级**：ES 对 backend `required:false`；不可用时 `/api/v1/search` 明确返回 503「搜索服务暂不可用」（非静默空、非 500 崩），主链路（MySQL+Redis）零影响。
- **前端最小载体**：社区搜索框 keyword 非空时切到 `/api/v1/search`（新增 `searchPosts` API 层，复用 #13 `CursorPageResult` 类型）；结果用 `HighlightText` 安全渲染 `<mark>` 高亮 + 复用 #13「加载更多」无限滚动（search_after）；不重做 UI、不做独立搜索页；keyword 为空行为不变。

### 变更清单（按工单）

| 工单 | 交付 | 关键文件 | commit |
|---|---|---|---|
| se-01 | ES+ik spike go/no-go=**GO**（Boot 4.1.1 下 Spring Data ES 6.1.1 可 index+search 中文，ik 生效，JsonpMapper 序列化不失真，FlatMessage 可 Jackson 解析） | docs/tickets/se-01-es-spike.md | d3e0dbe |
| se-02 | CDC 部署基建：ES(ik 自建镜像)+canal-server+MySQL binlog(ROW/FULL)+canal 复制账号+Kibana profile+backend ES 软依赖 | compose.yml、docker/*、application.properties | 9890638 |
| se-03 | `PostSearchDoc` mapping（ik_max_word/ik_smart + boost）+ `PostIndexService` 回源组装/幂等 upsert/全量重建切别名 + `SearchIndexManager` | search/PostSearchDoc、PostIndexService、SearchIndexManager | fe8b64d |
| se-04 | `GET /api/v1/search` 读路径：`SearchQueryService`（multi_match+boost+高亮+过滤+三排序+search_after+软依赖降级）+ `SearchController` + SaToken 白名单 | search/SearchQueryService、SearchController、PostSearchItem、SearchSort | 79aa9e3 |
| se-05 | CDC 消费者：`FlatMessage` DTO + `PostCdcHandler`（→postId 集合→index/delete 分发）+ `PostCdcStreamConfig` Consumer bean + 开关 | search/cdc/FlatMessage、PostCdcHandler、PostCdcStreamConfig | 4d62846 |
| se-06 | 前端搜索接入：`searchPosts` API + `searchItemToGuide` 映射 + `HighlightText` 安全高亮 + search-page 无限滚动 + 503 降级 | front/src/lib/api.ts、post-view.ts、components/HighlightText、search-page.tsx | 8f92741 |
| se-07 | S1~S4 测试+perf：`SearchPerfBenchmark`（LIKE vs ES 规模扫描）+ `search-cdc-smoke.mjs`（CDC 端到端延迟）+ 接入 CI deployment job + canal partitionsNum 修复 | perf/SearchPerfBenchmark、scripts/search-cdc-smoke.mjs、ci.yml、compose.yml | 41bddad |
| — | code-review 修复：`SearchIndexBootstrap` 启动软失败引导（消除全新栈搜索 503）+ 补 S2 恢复可搜测试 | search/SearchIndexBootstrap、SearchIndexManager、PostIndexServiceIntegrationTest | 0a0045d |
| se-08 | 学习复盘 + Issue #75 回写 + PR | 本文 + README + PR | — |

## 2. 关键难点解析

### 2.1 ⚠️ canal→RocketMQ producer NPE（CDC 静默不通的致命踩坑，vibe learning）

**现象**：全栈起好后 `GET /api/v1/search` 恒 503，ES 无 `post_search` 索引/别名，RocketMQ 无 `kuros-post-cdc` topic，backend 消费者已启动却收不到任何消息——CDC 端到端**从未打通**，但所有单测/门控集成测试全绿（测不出：单测直接喂 FlatMessage 给 handler，绕过了真实 canal producer）。

**定位**：canal 应用日志写在容器内文件不写 stdout（`docker compose logs` 只见 wrapper 脚本），须 `docker compose exec -T canal-server` 读 `/home/admin/canal-server/logs/kuros/kuros.log`——刷了 **1503 次** `ERROR CanalRocketMQProducer - null / NullPointerException at CanalRocketMQProducer.send:242`。

**根因**（canal-1.1.8 源码 `CanalRocketMQProducer.send`）：配了 `flatMessage=true` + `partitionHash` 非空 → 命中「分区合并」分支；`for (int i = 0; i < partitionNum; i++)` 的 `partitionNum` 是装箱 `Integer`，经三级回退取值全 null（`parseDynamicTopicPartition`→null；`getTopicDynamicQueuesSize`（需 `enableDynamicQueuePartition`，未开）→null；`destination.getPartitionsNum()`=`canal.mq.partitionsNum`，**未配**→null）。循环条件自动拆箱即 NPE——每条 binlog 事件发送都崩、topic 永不创建、CDC 全程静默不通。

**修复**：`compose.yml` canal-server env 显式声明 `canal.mq.partitionsNum: "4"`（对齐 `broker.conf` 的 `defaultTopicQueueNums=4`：partitionHash 把同一 postId 稳定哈希到 4 队列之一保序，且分区数 ≤ topic 队列数才不会发到不存在的队列）。重建 canal-server 后 **NPE=0**、topic 建好、首条 CDC 写入经别名引导创建 `post_search_v1`，冒烟三步全绿。

**教训**：(1) 真实中间件链路（canal/MQ）的 bug 单测测不出，**S3 端到端冒烟是不可替代的一层**——这正是 se-07 S3 的价值铁证。(2) canal 日志不落 stdout，排查须进容器读文件。(3) FlatMessage + partitionHash 组合下 `partitionsNum` 是**必填项**而非可选，canal 文档未明示，只在源码里暴露。

### 2.2 为什么回源组装而不直接用 binlog data（最关键正确性约束）

FlatMessage 的 posts 行**只有 posts 表列**，且值全为 String。但 ES 文档需要：① tags（在 `post_tags` join 表，binlog 单表行没有）；② authorName（在 kuros_user 跨库，binlog 更没有）；③ 类型转换（String→LocalDateTime/long，按 sqlType 转易错）。若硬用 binlog data 拼文档，tags/authorName 无来源、类型转换脆弱。

解法：消费者只从 FlatMessage 提取变更的 **postId 集合**（`data[].id` / `pkNames`；`post_tags` 变更据其 `post_id` 列同样触发对应帖子重组），再**回源**——JPA 取 `CommunityPost`（含 tags 关联）+ Feign 查 authorName → 组装完整 `PostSearchDoc` → 按 `_id=postId` upsert。代价是多一次 DB 读 + Feign，但 CDC 是异步链路，可接受；收益是天然解决 tags/authorName 来源 + 类型安全 + 幂等（重复消费/重试不产生副本）。

### 2.3 ik_max_word vs ik_smart（分词粒度分工）

- **建索引用 `ik_max_word`**：细粒度，把「鸣潮攻略」切成「鸣潮/攻略/鸣潮攻略」等所有可能词，**召回优先**——索引里词越全，搜索越不容易漏。
- **搜索用 `ik_smart`**：粗粒度，把查询词切成最合理的少量词，**精度优先**——避免查询词过度切分导致召回一堆不相关文档、打分被稀释。

这是 ik 的标准用法（建索引细、搜索粗）。实测隔字召回（N=5000，标题「鸣潮的攻略详解」搜「鸣潮攻略」）：`LIKE` 命中=**false**（子串不连续漏召回），ES ik=**true** 且排首位（分词后 term 匹配 + boost 打分）——这正是「为什么需要 ES」的功能性铁证。

### 2.4 search_after vs from-size 深翻（呼应 #13 拒 offset）

ES `from-size` 深翻要协调所有分片各取 `from+size` 条、汇总重排后丢弃前 from 条，O(from) 退化——与 #13 拒 offset 同理。`search_after` 用上一页最后一条的 **sort values** 作游标，下一页从它之后 seek，翻页耗时与深度**无关**。

关键工程细节：**非 relevance 排序必须附加 tie-breaker**（如 `postId` keyword）保证 search_after 全序稳定——否则同 `publishedAt`/同热度的文档排序不确定，翻页会丢/重。这正是 se-04 调试踩的坑：`postId` 起初仅标 `@Id` 未进 mapping（无 keyword 字段），排序 tie-breaker 失败导致 9 用例 503，修复是给 `PostSearchDoc.postId` 加 `@Field(type=Keyword)`。nextCursor 编码 search_after sort values（不透明 base64url，复用 #13 `CursorCodec` 风格），响应复用 #13 `CursorPageResult`（items+nextCursor+hasMore，**不带 total**）。

### 2.5 删除语义：逻辑删 + status 过滤（可逆）

`CommunityPost` 是逻辑删除（`delete(now)`→status=DELETED，**领域层无 restore()**，恢复靠直接改 status），binlog 里是 UPDATE 而非 DELETE。ES 文档保留 `status` 字段，**搜索时 filter `status=PUBLISHED`**——status→DELETED 经 CDC 同步后文档自动被过滤，不物理删。恢复 PUBLISHED 经 CDC 同步后自动重新可搜，无需重新索引。这与 CONTEXT「已删除帖子可恢复（内容处置）」语义一致，比删文档简单可逆。兜底：若出现物理 DELETE 事件（如未来清理任务）或回源时查不到帖子，消费端 `delete by _id`。

### 2.6 ES 软依赖降级 + 启动引导（code-review 修复的降级语义收窄）

ES 对 backend 是软依赖（`required:false`），`SearchQueryService` catch `RuntimeException`（含 index_not_found）→ `ServiceUnavailableException` → 503；catch **仅包裹** `operations.search()`，游标解码（→400 INVALID_CURSOR）/buildQuery 在 try 外不被吞。

**code-review 发现的降级语义过宽（最实质问题）**：别名 `post_search` 原**只在写路径**（`PostIndexService#index` 首次索引）懒创建。于是一个全新部署、尚未发帖也未跑全量重建的栈上，`/api/v1/search` 会因 index_not_found 被 catch 转成 503「搜索服务暂不可用」——可 ES 其实活得好好的，只是索引没建，这个信号误导用户/运维以为搜索挂了（S3 冒烟脚本不得不专门容忍初始 503，即此摩擦的外部证据）。

**修复**：新增 `SearchIndexBootstrap` `@EventListener(ApplicationReadyEvent)` → `SearchIndexManager#ensureAliasIfAvailable()`（内部软失败 try/catch，ES 不可用只记 warn，保留 se-02「ES 软依赖、宕机不拖垮应用」承诺）。用 `ApplicationReadyEvent`（而非 `@PostConstruct`）是刻意的——此时上下文已刷新、readiness 探针已过，即便 ES 慢到连接超时也只延后一条日志、绝不阻塞启动完成。引导成功后全新栈搜索直接返回空结果（语义正确），503 收窄为「ES 真的连不上」这一种情形。

**活全栈实证**：wipe ES 索引 → `force-recreate` backend → 别名自动创建 `post_search_v1`（docs.count=0，无发帖/无重建）→ backend 日志 `SearchIndexManager ...post_search -> post_search_v1` 紧跟 `Started` → `curl /api/v1/search` 返 **HTTP 200 `{items:[],nextCursor:null,hasMore:false}`** 而非 503。

### 2.7 Spring Boot 4 下的版本基线与序列化

Spring Boot 4.1.1 → spring-data-elasticsearch **6.1.1**（BOM 托管）+ elasticsearch-java/rest5-client **9.4.5** + ES 服务端/analysis-ik **9.4.5**（严格等版本，自建带 ik 的 Dockerfile）+ Canal **1.1.8**（支持 MySQL 8 `caching_sha2_password`）+ RocketMQ 5.3.1。ES 客户端默认 Jackson 2 mapper，与项目已有 Jackson 2（sa-token）共存无冲突。spike 踩坑备忘：`NativeQuery` 在 6.1.1 迁至 `org.springframework.data.elasticsearch.client.elc` 包；Testcontainers 等待策略用 `forPort` 而非 `withPort`。

## 3. 简历 STAR 写法

- **S（情境）**：高并发内容社区的搜索仍是 MySQL `LIKE '%x%'`——前导通配符令索引失效全表扫、无中文分词（隔字漏召回）、搜不到正文、无相关性/高亮。且引入 ES 后 MySQL 权威源与 ES 异构副本的一致性成为第二重矛盾。
- **T（任务）**：把搜索升级为 ES ik 全文检索 + CDC 增量索引，与 #11/#12/#13 合成完整读写检索闭环，要求**可被面试深挖 30 分钟、收益可验证、不编造数字**。
- **原方案为何不行**：① 应用层双写——漏捕获非应用来源的 DB 变更（手工改库/批处理/迁移）+ ES 写失败与 MySQL 事务无法原子 + 侵入业务；② 应用层发 RocketMQ 业务事件——同双写的漏捕获问题（删除/改库不发事件）；③ canal-client 直连——backend 与 canal 强耦合、无 MQ 削峰/重试/死信；④ 直接用 binlog data 拼文档——缺 tags(join)/authorName(跨库)、String 转类型易错；⑤ from-size 分页——深翻 O(from) 退化；⑥ 见 DELETED 就删 ES 文档——恢复要重新索引、时序更复杂。
- **A（行动）**：
  - **ES 全文检索**：`PostSearchDoc`（ik_max_word 建索引 / ik_smart 搜索，title^3>excerpt^2>content^1）+ multi_match + term/terms 过滤 + highlight + 三排序 + search_after（postId tie-breaker 保序）。
  - **Canal CDC**：MySQL binlog(ROW/FULL) → Canal(partitionHash by postId 保序) → RocketMQ `kuros-post-cdc` → backend orderly 消费者，以 binlog 为唯一事实源、业务零侵入、复用 #11 顺序/重试/死信。
  - **回源组装 + 幂等**：消费者取 postId → JPA(posts+tags) + Feign(authorName) → 组装 → `_id=postId` upsert。
  - **删除可逆**：逻辑删靠 status 过滤，不物理删文档。
  - **index alias 零停机重建** + 启动引导软失败（消除全新栈搜索 503）。
  - **ES 软依赖降级**：不可用返 503「搜索暂不可用」，主链路零影响。
  - **前端最小载体**：search-page 无限滚动 + `HighlightText` 安全高亮（XSS 防护）+ 503 降级。
- **R（结果，可验证的结构性收益）**：
  - **性能量化**（`SearchPerfBenchmark` 实测，§5）：LIKE 随 N 近线性上涨（10k→50k 数据 5x，LIKE 14.4→66.7ms 约 4.6x），ES 近乎恒定（~16ms 与语料规模解耦）；50k 时 ES 快 **4.22x**，并诚实呈现小规模交叉点（2k 时 ES 因固定查询开销反而 0.35x）。
  - **隔字召回**：LIKE 搜「鸣潮攻略」命中「鸣潮的攻略详解」=false，ES ik=true 且排首位（§5）。
  - **CDC 端到端延迟实测**（全栈冒烟，§5）：发帖→可搜 1853ms、改帖→同步 569ms、逻辑删→消失 1087ms，全生命周期 postId 一致。
  - **零回归**：全量 `mvn test` **110/0/19 BUILD SUCCESS**，与 se-05e 基线一致。
  - **验证**：门控集成测试全绿（S1 搜索契约 11/11、S2 索引 7/7、CDC 单测 8/8、perf 2/2），前端 lint+build 全绿。

## 4. 原理详解

### 4.1 CDC 增量索引全链路
```
MySQL posts/post_tags 变更（发帖/改/逻辑删/改标签）
   └─ binlog（ROW + FULL image）
         ▼
   Canal 伪装 slave（serverMode=rocketMQ, flatMessage=true）
   └─ partitionHash = kuros\.posts:id  ← 同一 postId 稳定哈希到固定队列，保序
   └─ canal.mq.partitionsNum = 4       ← ⚠️ 必填！缺失则 producer NPE（§2.1）
         ▼
   RocketMQ topic kuros-post-cdc（复用 #11 namesrv/broker, defaultTopicQueueNums=4）
         ▼
   backend Spring Cloud Stream 消费者（postCdcConsumer, orderly + 重试 + %DLQ%）
   └─ PostCdcHandler.handle(FlatMessage)
         ├─ 解析 data[].id / pkNames → 变更的 postId 集合
         ├─ post_tags 变更据 post_id 列同样触发对应帖子
         └─ 对每个 postId：
               ├─ UPDATE/INSERT → PostIndexService.index(postId)   ← 回源组装
               └─ 物理 DELETE   → PostIndexService.delete(postId)  ← 兜底
```

### 4.2 消费端回源组装 + 幂等 upsert
```
PostIndexService.index(postId)
   ├─ ensureAlias()（首次写入兜底懒创建，与启动引导双路径，均软失败）
   ├─ JPA 回源：CommunityPost（含 tags 关联）；查不到 → delete by _id 兜底（物理删）
   ├─ Feign 回源：UserDirectoryFacade → authorName（跨库 kuros_user）
   ├─ 组装 PostSearchDoc（postId=_id, title/excerpt/content, tags, authorName,
   │                        category/type/status, likeCount/commentCount/viewCount, publishedAt）
   └─ operations.save(doc)  ← _id=postId upsert，天然幂等（重复消费/重试不产生副本）
```

### 4.3 搜索读路径 + search_after
```
GET /api/v1/search?keyword=&category=&tag=&type=&sort=&cursor=&limit=   （SaToken 白名单放行）
   └─ SearchQueryService.search(keyword, category, tag, type, sort, cursor, limit)
         ├─ decode cursor → search_after sort values | null（非法 → 400 INVALID_CURSOR，在 try 外）
         ├─ buildQuery（在 try 外，不被降级 catch 吞）：
         │     multi_match(keyword; title^3, excerpt^2, content^1; analyzer=ik_smart)
         │     + filter: term(status=PUBLISHED) + term(category/type) + terms(tags)
         │     + highlight(title, content)
         │     + sort: relevance=_score | latest=publishedAt | hot=计数  (+ postId tie-breaker)
         ├─ try { operations.search() }  ← ⚠️ catch 仅包裹这一步
         │     catch RuntimeException(含 index_not_found) → ServiceUnavailableException → 503
         ├─ hasMore = hits.size() > limit（多取一条探测）
         └─ nextCursor = encode(last hit sort values)
   └─ ApiResponse<>(CursorPageResult<PostSearchItem>(items, nextCursor, hasMore))  ← 不带 total
```

### 4.4 别名生命周期（两条软失败触发路径）
```
别名 post_search → 真实索引 post_search_v{n}
   ├─ 路径① 启动引导：SearchIndexBootstrap @EventListener(ApplicationReadyEvent)
   │        → SearchIndexManager.ensureAliasIfAvailable()（软失败：ES 不可用只记 warn）
   │        → resolveCurrentIndex() 空则 createVersionedIndex(v1) + switchAlias
   ├─ 路径② 首次写入兜底：PostIndexService.index() 首行 ensureAlias()（懒创建）
   └─ 全量重建（零停机 mapping 升级）：bulk 写 v{n+1} → 原子 switchAlias → 删旧索引
```

## 5. 实测收益（se-07 真实数据，禁止编造）

> **采集方式**：CDC 延迟来自 `scripts/search-cdc-smoke.mjs`（全栈 compose 真链路，轮询间隔 500ms，2026-09-23 采集，postId=49a06750）；perf 对比来自 `SearchPerfBenchmark`（`-Dperf=true -Dkuros.it.es=true` 门控，Testcontainers ES(ik)+H2，每点 repeat=20 取均值，keyword=鸣潮攻略，pageSize=20）。
> **环境诚实声明**：perf 数据来自 Testcontainers ES + 进程内 H2，展示「随规模变化的趋势与量级差异」，非生产 MySQL 绝对 RT。

### 5.1 S4 — LIKE 全表扫 vs ES ik 倒排（规模扫描）

| scale(posts) | LIKE avg(ms) | ES avg(ms) | LIKE/ES |
|---|---|---|---|
| 2000 | 13.166 | 37.303 | **0.35x**（小规模 ES 固定查询开销占优，反而慢） |
| 10000 | 14.425 | 16.487 | **0.87x**（交叉点） |
| 50000 | 66.726 | 15.823 | **4.22x**（ES 快 4.2 倍） |

- **LIKE 随 N 近线性上涨**：10k→50k 数据量 5x，LIKE 14.4→66.7ms 约 4.6x（前导通配符无法走 B+Tree 索引，退化 O(n) 全表扫）。
- **ES 近乎恒定**：~16ms 不随总量涨（ik 分词建倒排索引，查询复杂度与语料规模解耦）。
- **诚实呈现交叉点**：小规模下 ES 因网络往返 + 固定查询开销反而略慢，过交叉点后被越拉越开——这是「为什么需要 ES」的量化依据，而非无条件吹 ES。

### 5.2 隔字召回（功能性铁证，N=5000）

标题「鸣潮的攻略详解」搜「鸣潮攻略」：`LIKE` 命中=**false**（子串不连续漏召回），ES ik=**true** 且排首位（分词后 term 匹配 + boost 打分）。

### 5.3 S3 — CDC 端到端延迟（全栈真链路）

真实全链路（登录→发帖→MySQL binlog→canal 伪装 slave→RocketMQ `kuros-post-cdc`(FlatMessage)→backend `postCdcConsumer`→`PostCdcHandler` 回源组装→`PostIndexService.index`→ES→`GET /api/v1/search` 命中）：

| 阶段 | 延迟 | 轮询次数 |
|---|---|---|
| 发帖→可搜（索引） | **1853 ms** | 4 |
| 改帖→搜到新内容（更新） | **569 ms** | 2 |
| 逻辑删→搜索消失（删除同步） | **1087 ms** | 3 |

发帖→可搜首次略高（含 canal 冷启动后首条消息 + 别名引导的一次性开销）；改帖/删帖走已建好的别名，收敛更快。全生命周期同步正确（postId 全链路一致）。

### 5.4 测试覆盖清单（结构性指标，可复现）

| 测试类 | 用例数 | 覆盖 | 门控 |
|---|---|---|---|
| SearchHttpContractIntegrationTest | 11 | ik 分词命中/多字段 boost/高亮/status 过滤/三排序/search_after 深翻/CursorPageResult 契约 | `-Dkuros.it.es=true` |
| SearchUnavailableIntegrationTest | (含上) | ES 不可用降级 503 | `-Dkuros.it.es=true` |
| PostIndexServiceIntegrationTest | 7 | 回源组装/全量重建切别名/DELETED 过滤/幂等/物理删兜底 + **恢复可搜**、**启动引导后搜索返空而非 503**（code-review 补 2 例） | `-Dkuros.it.es=true` |
| PostCdcHandlerTest | 8 | FlatMessage→postId 集合→index/delete 分发（纯单测） | 常开 |
| SearchPerfBenchmark | 2 | 规模扫描 + 隔字召回（LIKE vs ES） | `-Dperf=true -Dkuros.it.es=true` |

> **全量后端套件（2026-09-23 本地实跑，等价 CI `./mvnw test`）**：`Tests run: 110, Failures: 0, Errors: 0, Skipped: 19`，BUILD SUCCESS（08:22 min），与 se-05e 基线完全一致，零回归。19 skipped = 门控测试（ES 集成/perf/spike，需 `-D` 开关，CI 裸 `mvn test` 不跑）。ES 相关集成测试用独立 H2 库名 + `reuseForks=false` 每类独立 JVM，与主套件隔离。前端 `npm run lint`（0 error）+ `npm run build`（Compiled successfully + tsc 通过）均绿。

### 5.5 已知限制（诚实呈现）

1. **DB↔ES 最终一致**：binlog→Canal→MQ→ES 秒级延迟窗口内新帖搜不到（社区场景可接受，实测 §5.3）。
2. **CDC 链路挂掉期间索引停更**：恢复后 Canal 从 binlog position 续传补齐（binlog 保留期内不丢）。
3. **Canal 长时间宕机且 binlog 已过期**（`binlog_expire_logs_seconds`）的变更会丢失，需**全量重建兜底**。
4. **authorName 冗余进索引**：用户改名需 CDC 捕获 user 库变更或 TTL 兜底（改名低频，本片未强化，留下一片）。
5. **热度排序用的计数是 CDC 快照**、非 #11 实时值（允许滞后；实时值可在组装时从 Redis 叠加）。
6. **`reindexAll` 全量入内存**：demo 规模可接受，生产海量数据需分页 bulk（已自注释）。
7. **D8 离线包兜底半做**：ES 镜像 ik 插件国内拉取的离线包兜底为可选路径，本片以可配镜像源为主。

## 6. 面试追问链回答清单（≥5 条）

**Q1：为什么不用应用层双写或发业务事件，非要上 Canal？**
A：双写/业务事件都有**漏捕获**问题——只对「经过应用代码」的变更生效，DBA 手工改库、批处理、数据迁移这些非应用来源的 DB 变更捕获不到，索引就和 DB 悄悄不一致了。Canal 伪装 slave 订阅 binlog，以 binlog 为**唯一事实源**，任何来源的变更都会落 binlog 被捕获；且对业务代码**零侵入**（发帖逻辑不用管 ES）。另外双写的一致性也难保证——ES 写失败如何与 MySQL 事务原子？CDC 把这件事解耦成异步最终一致，复用 #11 的 MQ 顺序/重试/死信基建。

**Q2：Canal 挂了怎么办？会不会丢变更？**
A：分两种。短暂故障——Canal 记录了消费的 binlog position，恢复后从 position **续传**补齐，binlog 保留期内不丢。长时间宕机且 binlog 已被 `binlog_expire_logs_seconds` 清理——那段变更确实丢了，兜底是**全量重建**（遍历 posts bulk 写 `v{n+1}` → 原子切别名 → 删旧索引，零停机）。CDC 链路任一环挂只导致索引停更，主链路（MySQL+Redis 发帖/读帖/Feed/详情）零影响，因为 ES 是软依赖。

**Q3：binlog 里 posts 行只有单表列，ES 文档要的 tags 和 authorName 从哪来？**
A：这正是本切片最关键的「回源组装」约束。tags 在 `post_tags` join 表、authorName 在 kuros_user 跨库，binlog 单表行都没有，而且值全是 String 需转 LocalDateTime/long。所以我**不直接用 binlog data 拼文档**，而是只提取变更的 postId → 回查 DB（JPA 取 posts + tags）+ Feign 查 authorName → 组装完整文档 → 按 `_id=postId` upsert。多一次 DB 读 + Feign，但 CDC 是异步链路可接受，换来 tags/authorName 来源正确 + 类型安全 + 幂等。Canal 也因此要订阅 posts + post_tags 两表，改标签也触发对应帖子重组。

**Q4：ik_max_word 和 ik_smart 有什么区别？为什么建索引和搜索用不同的？**
A：ik_max_word 细粒度，把「鸣潮攻略」切成所有可能的词（鸣潮/攻略/鸣潮攻略），**召回优先**；ik_smart 粗粒度，切成最合理的少量词，**精度优先**。建索引用 ik_max_word 让索引里词尽量全（不漏召回），搜索用 ik_smart 让查询词不过度切分（避免召回一堆不相关文档、稀释打分）。实测隔字场景：标题「鸣潮的攻略详解」搜「鸣潮攻略」，LIKE 因子串不连续漏召回，ES ik 分词后能命中且排首位。

**Q5：search_after 和 from-size 有什么区别？为什么不用 from-size？**
A：from-size 深翻要协调所有分片各取 `from+size` 条、汇总重排后丢弃前 from 条，O(from) 退化，翻到很深的页越来越慢——和 #13 拒 offset 是同一个道理。search_after 用上一页最后一条的 sort values 作游标，下一页从它之后 seek，翻页耗时与深度无关。代价是不支持跳页（只能下一页），但搜索就是无限往下翻的语义，不需要跳页。有个坑：非 relevance 排序必须附加 tie-breaker（我用 postId keyword）保证全序稳定，否则同发布时间/同热度的文档排序不确定，翻页会丢或重——我 se-04 就踩过，postId 起初没进 mapping 导致 tie-breaker 失败。

**Q6：删除帖子时 ES 文档怎么处理？为什么不直接删文档？**
A：`CommunityPost` 是逻辑删除（status→DELETED，binlog 里是 UPDATE 不是 DELETE）。我保留 ES 文档的 status 字段，搜索时 filter `status=PUBLISHED` 自动排除。不物理删是因为产品语义是「已删除帖子可恢复」——恢复只需 status 改回 PUBLISHED，经 CDC 同步后文档自动重新可搜，不用重新索引，删除/恢复的时序也更简单。真出现物理 DELETE 事件（如未来清理任务）或回源时查不到帖子，消费端兜底 delete by _id。

**Q7（加分）：CDC 怎么保证同一帖子的变更有序？**
A：两层保序。Canal 配 `partitionHash=kuros\.posts:id`，把同一 postId 的变更稳定哈希到 RocketMQ 固定队列；backend 消费者用 orderly 消费。这样同一帖子的「发布→编辑→删除」按发生顺序进 ES，不会出现旧版本覆盖新版本。这里踩了个大坑：flatMessage + partitionHash 组合下 `canal.mq.partitionsNum` 是必填的，不配的话 canal producer 三级回退取值全 null、循环拆箱 NPE，每条 binlog 发送都崩、topic 永不创建、CDC 全程静默不通——而且所有单测都测不出（单测直接喂 FlatMessage 给 handler，绕过真实 producer），只有 S3 端到端冒烟才暴露。

**Q8（加分）：全新部署还没发帖，搜索会怎样？**
A：这是 code-review 抓出来的降级语义过宽问题。别名原本只在首次写入时懒创建，全新栈没发帖时 `/search` 会因 index_not_found 被降级 catch 转成 503「搜索暂不可用」——但 ES 明明活得好好的，只是索引没建，这个 503 会误导运维以为搜索挂了。修复是加了 `SearchIndexBootstrap` 在 `ApplicationReadyEvent` 时主动引导别名（软失败，ES 不可用只记 warn 不阻塞启动），引导成功后全新栈搜索直接返回空结果（200，语义正确），503 收窄成「ES 真的连不上」这一种情形。

## 7. 技术选型对比

### 7.1 DB→ES 数据同步方案对比

| 维度 | 应用层双写 | 业务事件(MQ) | **Canal CDC（本项目）** |
|---|---|---|---|
| 捕获非应用变更（改库/批处理） | ✗ 漏 | ✗ 漏 | **✓ binlog 全捕获** |
| 业务侵入 | 高 | 中 | **零侵入** |
| 与 MySQL 事务一致性 | 难原子 | 最终一致 | **最终一致（binlog 为源）** |
| 基建复用 | — | 复用 #11 MQ | **复用 #11 MQ + canal** |
| 复杂度 | 低 | 中 | 中高（多 canal 组件） |

### 7.2 搜索分页方案对比

| 维度 | from-size | **search_after（本项目）** |
|---|---|---|
| 深翻复杂度 | O(from) 退化 | **与深度无关** |
| 跳页 | 支持 | 不支持（只下一页） |
| 是否需 total | 需要 | **不需要（CursorPageResult 不带 total）** |
| 全序稳定 | — | 需 tie-breaker（postId keyword） |
| 适用 | 后台/需跳页 | **搜索无限滚动（本项目）** |

### 7.3 关键决策的备选方案（ADR 0007）

| 决策点 | 选定 | 拒绝的备选 | 拒绝理由 |
|---|---|---|---|
| 数据同步 | Canal CDC | 双写 / 业务事件 / canal-client 直连 | 漏捕获非应用变更 / 侵入 / 强耦合无削峰 |
| 文档来源 | 回源组装（DB+Feign） | 直接用 binlog data | 缺 tags(join)/authorName(跨库) + String 转类型易错 |
| 分词 | ik_max_word 建 / ik_smart 搜 | 单一粒度 | 建索引召回优先、搜索精度优先，分工才最优 |
| 深翻 | search_after | from-size | O(from) 退化（呼应 #13 拒 offset） |
| 删除语义 | 保留 status + filter | 见 DELETED 删文档 | 逻辑删可逆，恢复只需改 status，时序更简单 |
| ES 客户端 | Spring Data ES 6.1.1 主力 | 纯原生 ES Java Client | Boot 4 已迁 Rest5Client，手动装配 mapper 易踩版本细节 |
| 索引重建 | index alias 原子切换 | 停写重建 | mapping 变更零停机 |
| 范围克制 | 仅帖子搜索 | 含评论 Comment | 评论非高频热点，扩范围稀释主叙事（对齐 #13 克制） |

## 8. 面试叙事模板

**30 秒电梯版**：我把社区搜索从 MySQL `LIKE '%x%'` 升级成 Elasticsearch ik 全文检索 + Canal CDC 增量索引。LIKE 有三重退化——前导通配符全表扫、无中文分词（隔字漏召回）、搜不到正文，换成 ES 后用 ik 分词（建索引 ik_max_word、搜索 ik_smart）、多字段加权 match、高亮、search_after 深翻不退化。数据同步用 Canal 伪装 slave 订阅 binlog 经 RocketMQ 到消费者写 ES，以 binlog 为唯一事实源、业务零侵入。关键设计是消费端「回源组装」——binlog 只有单表行，我拿 postId 回查 DB + Feign 补齐 join 的 tags 和跨库的 authorName 再按 `_id=postId` 幂等 upsert；逻辑删靠 status 过滤保可逆；ES 是软依赖，挂了搜索优雅降级、主链路零影响。实测 50k 帖子下 ES 比 LIKE 快 4.2 倍，CDC 发帖到可搜秒级延迟。

**2 分钟详细版**：在 30 秒版基础上补充——
① **为什么此刻做**：#11 写路径异步化、#12 Feed、#13 读路径加固已是读写闭环，搜索是社区另一个最高频入口，与前三片合成「高并发内容社区」完整叙事；
② **为什么 Canal 不双写**：双写/业务事件都漏捕获非应用来源的 DB 变更（改库/批处理/迁移），Canal 以 binlog 为唯一事实源全捕获 + 零侵入；
③ **回源组装这个关键约束**：binlog posts 行只有单表列且全是 String，ES 文档要的 tags 在 join 表、authorName 在跨库，所以只取 postId 回查 DB+Feign 组装再幂等 upsert；
④ **保序 + 幂等**：Canal partitionHash by postId + RocketMQ orderly 保同帖变更有序，`_id=postId` upsert 保重复消费不产生副本；
⑤ **删除可逆**：逻辑删 status→DELETED，搜索 filter PUBLISHED，不物理删文档，恢复自动重新可搜；
⑥ **踩过的两个大坑**：一是 canal flatMessage+partitionHash 下 partitionsNum 必填，不配则 producer NPE、CDC 全程静默不通，且单测测不出、只有端到端冒烟能暴露；二是 search_after 非 relevance 排序必须加 postId tie-breaker，否则翻页丢/重；
⑦ **降级语义收窄**：全新栈没发帖时别名没建，搜索会误报 503，加 `SearchIndexBootstrap` 在 ApplicationReadyEvent 软失败引导别名，搜索返 200 空、503 收窄为「ES 真连不上」；
⑧ **收益验证（真实数据，禁止编造）**：`SearchPerfBenchmark` 实测 LIKE 随 N 近线性涨、ES 近乎恒定，50k 时 ES 快 4.22x（并诚实呈现小规模交叉点 0.35x）；隔字召回 LIKE=false / ES ik=true；CDC 端到端延迟发帖→可搜 1853ms、改帖 569ms、逻辑删 1087ms；全量套件 110/0/19 零回归。
