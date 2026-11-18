## 切片 #14：全文检索 + CDC 增量索引（Elasticsearch ik 分词 + Canal 订阅 binlog）

关联 Issue #75 · Spec `docs/specs/fulltext-search-cdc.md` · ADR `docs/adr/0007-fulltext-search-cdc.md` · 复盘 `docs/learning/14-fulltext-search-cdc.md`

与 #11（写）+#12（Feed）+#13（读）合成「高并发内容社区」完整读写检索闭环。**所有性能/延迟数字均为实测，禁止编造。**

### 做了什么

- **ES 全文检索读路径**：`PostSearchDoc`（ik_max_word 建索引 / ik_smart 搜索，title^3>excerpt^2>content^1）+ `GET /api/v1/search`（multi_match + 高亮 + category/tag/type 过滤 + relevance/latest/hot 三排序 + search_after 深翻 + `CursorPageResult` 复用 #13 契约，不带 total）。
- **Canal CDC 增量索引**：MySQL binlog(ROW/FULL) → Canal(partitionHash by postId 保序) → RocketMQ `kuros-post-cdc`(FlatMessage) → backend orderly 消费者 → `PostCdcHandler` → `PostIndexService` 回源组装（JPA posts+tags + Feign authorName）→ `_id=postId` 幂等 upsert。
- **删除可逆**：逻辑删 status→DELETED，搜索 filter PUBLISHED，不物理删文档，恢复自动重新可搜。
- **index alias 零停机重建** + 启动引导软失败（`SearchIndexBootstrap`@ApplicationReadyEvent，消除全新栈搜索 503）。
- **ES 软依赖降级**：不可用返 503「搜索暂不可用」，主链路（MySQL+Redis）零影响。
- **前端最小载体**：search-page 无限滚动 + `HighlightText` 安全高亮（XSS 防护）+ 503 降级；不重做 UI、不做独立搜索页。

### 测试与实测数据

- 门控集成测试全绿：S1 搜索契约 **11/11**、S2 索引 **7/7**（含 code-review 补的恢复可搜、启动引导后返空非 503）、CDC 单测 **8/8**、perf **2/2**。
- 全量套件 `mvn test` **110/0/19 BUILD SUCCESS**（08:22 min），与 se-05e 基线一致**零回归**；前端 lint+build 全绿。
- **LIKE vs ES 规模扫描**：2k=0.35x（ES 固定开销占优）→ 10k=0.87x（交叉点）→ 50k=**4.22x**（ES 快 4.2 倍）；LIKE 随 N 近线性涨、ES 近乎恒定。隔字召回：LIKE=false / ES ik=true 排首位。
- **CDC 端到端延迟**（全栈真链路）：发帖→可搜 1853ms、改帖→同步 569ms、逻辑删→消失 1087ms。

### code-review 结论（双轴）

- **Standards**：0 硬违规，命名与 CONTEXT.md 一致，5 高风险点全过（XSS/503 catch 作用域/CDC 幂等/partitionsNum）。
- **Spec**：3 缺失+2 蔓延+3 偏差。已修复 (a)1（补 S2 恢复可搜测试，工单 5/5→7/7）+ (c)1（启动软失败引导收窄降级语义，活全栈实证 search 返 200 空非 503）；余 ACCEPT/DEFER（详见 Issue #75 回写）。

### ⚠️ vibe learning — canal producer NPE

flatMessage+partitionHash 下 `canal.mq.partitionsNum` 未配 → producer 三级回退取值全 null → 拆箱 NPE（1503 次），CDC 全程静默不通、单测测不出（绕过真实 producer），只有 S3 端到端冒烟暴露。修复：compose.yml 补 `partitionsNum: "4"`（对齐 broker defaultTopicQueueNums=4）。

### 已知限制

DB↔ES 最终一致（秒级窗口）；CDC 挂掉期间索引停更（恢复从 binlog position 续传，过期需全量重建兜底）；authorName 冗余（改名靠 CDC 捕获 user 库或 TTL，留下一片）；热度排序用 CDC 计数快照非实时；reindexAll 全量入内存（demo 规模，生产需分页 bulk）。
