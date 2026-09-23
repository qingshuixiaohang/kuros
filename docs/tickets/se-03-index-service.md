# se-03: PostSearchDoc mapping + PostIndexService 回源组装/幂等 upsert

**What to build:** 让一个帖子能被正确索引进 ES——给定 postId，回查 DB（posts + tags）+ Feign（authorName）组装完整文档，按 `_id=postId` 幂等 upsert 进带 ik 分词的索引；逻辑删（status→DELETED）保留文档、靠搜索过滤排除；支持 index alias 零停机全量重建。这是搜索的数据来源（CDC 与手动 reindex 都调它）。

**Blocked by:** se-02（需 ES 运行 + 连接配置就位）

**Status:** done

- [x] 定义 `PostSearchDoc`（`@Document` + `@Field`）：postId(=_id)、title(text ik_max_word boost3)、excerpt(ik_max_word boost2)、content(ik_max_word boost1)、authorId(keyword)、authorName(text+keyword 冗余)、category/type/status(keyword)、tags(keyword[])、likeCount/commentCount/viewCount(long)、publishedAt(date)；searchAnalyzer=ik_smart
- [x] 实现 `PostIndexService.index(postId)`：JPA 取 CommunityPost(含 tags) + Feign(`UserDirectoryFacade`) 查 authorName → 组装 PostSearchDoc → `_id=postId` upsert（index 覆盖写，天然幂等）
- [x] **不直接用 FlatMessage data 拼文档**：回源组装解决 tags(join 表)/authorName(跨库)/String→类型 转换问题
- [x] 删除语义：status→DELETED 保留文档（搜索时 filter，本工单不物理删）；物理 DELETE 或回源查不到 → 兜底 delete by _id
- [x] index alias：真实索引 `post_search_v1` + 对外别名 `post_search`，读写走别名
- [x] 全量重建 + reindex 端点（**落在 `POST /api/v1/admin/search/reindex`**，见下方决策偏差）：遍历 posts bulk 写 `post_search_v{n+1}` → 原子切别名 → 删旧索引
- [x] TDD（S2 seam，Prior art #13 `TwoLevelCacheIntegrationTest`）：seed DB(posts+post_tags)→index(postId)→断言 ES 文档字段(tags 来自 join、authorName 来自 Feign 桩、status、计数)；status=DELETED 后文档保留；重复 index 幂等不产生副本
- [x] 编译级快验通过；现有测试不破坏

---

## 实现证据（实测）

**门控集成测试 `PostIndexServiceIntegrationTest`（`-Dkuros.it.es=true`，自建 ik 镜像 ES 9.4.5 + redis Testcontainers）：**

```
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 58.53 s
```

5 个用例覆盖验收标准：
1. `回源组装tags与authorName与计数与状态`——tags 来自 post_tags join（排序确定化 `["攻略","鸣潮"]`）、authorName 来自 Feign 桩「潮声档案员」、status=PUBLISHED、like=7/comment=3、publishedAt 经 JsonpMapper 序列化回读不丢；
2. `逻辑删保留文档且status更新为DELETED`——status→DELETED 后再索引，文档保留、status 随之更新（不物理删）；
3. `重复索引幂等不产生副本`——连续 index 3 次，countAll()==1（_id=postId 覆盖写）；
4. `全量重建零停机切别名且旧索引删除`——reindexAll 后别名从 post_search_v1 原子切到 v2、v1 exists()==false、两文档仍经别名可读、countAll()==2；
5. `物理删除或回源查不到时兜底删文档`——物理删 DB 行后再 index，回源查不到 → delete by _id，ES 无孤儿文档。

**决策偏差：reindex 端点 `/api/v1/internal` → `/api/v1/admin`。** 工单草案写的是 `POST /api/v1/internal/search/reindex`，但 backend 侧并无 `/internal/**` 免鉴权约定（那是 kuros-user 的入站内部 API 前缀）；backend 的 `SaTokenConfigure` 只对 `/api/v1/admin/**` 施加 `checkRole("ADMIN")`。故落在 `/api/v1/admin/search/reindex`，复用现成 ADMIN 角色门 + CSRF（`/api/**` 天然覆盖）即得到「仅运维可触发」语义，无需为全量重建新扩鉴权白名单（与 `AdminReportController` 同一约定）。

**Spring Data ES 6.1.1 API 要点（javap 查证）：** `AliasActionParameters.builder()`（静态工厂，`Builder()` 构造器 private）；`IndexOperations.alias(AliasActions)` / `getAliases(String...)`（键为真实索引名）/ `createMapping()→Document` / `putMapping(Document)`；`@Document(createIndex=false)` 防自动建同名真实索引，读写走别名；重建时 bulk 写入 `IndexCoordinates.of("post_search_v{n+1}")`（新索引坐标，非别名）后 refresh → 单次 `_aliases` 调用原子 remove 旧 + add 新。

**ES 软依赖验证：** test profile 连不上 Nacos（gRPC 9848 报错刷屏）但 Spring context 照常启动、测试全绿——佐证 ES/Nacos 均为懒连接软依赖，`ElasticsearchOperations` bean 创建不触发即时连接，现有测试不破坏。
