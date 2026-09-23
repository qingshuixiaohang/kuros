# se-03: PostSearchDoc mapping + PostIndexService 回源组装/幂等 upsert

**What to build:** 让一个帖子能被正确索引进 ES——给定 postId，回查 DB（posts + tags）+ Feign（authorName）组装完整文档，按 `_id=postId` 幂等 upsert 进带 ik 分词的索引；逻辑删（status→DELETED）保留文档、靠搜索过滤排除；支持 index alias 零停机全量重建。这是搜索的数据来源（CDC 与手动 reindex 都调它）。

**Blocked by:** se-02（需 ES 运行 + 连接配置就位）

**Status:** ready-for-agent

- [ ] 定义 `PostSearchDoc`（`@Document` + `@Field`）：postId(=_id)、title(text ik_max_word boost3)、excerpt(ik_max_word boost2)、content(ik_max_word boost1)、authorId(keyword)、authorName(text+keyword 冗余)、category/type/status(keyword)、tags(keyword[])、likeCount/commentCount/viewCount(long)、publishedAt(date)；searchAnalyzer=ik_smart
- [ ] 实现 `PostIndexService.index(postId)`：JPA 取 CommunityPost(含 tags) + Feign(`UserDirectoryFacade`) 查 authorName → 组装 PostSearchDoc → `_id=postId` upsert（index 覆盖写，天然幂等）
- [ ] **不直接用 FlatMessage data 拼文档**：回源组装解决 tags(join 表)/authorName(跨库)/String→类型 转换问题
- [ ] 删除语义：status→DELETED 保留文档（搜索时 filter，本工单不物理删）；物理 DELETE 或回源查不到 → 兜底 delete by _id
- [ ] index alias：真实索引 `post_search_v1` + 对外别名 `post_search`，读写走别名
- [ ] 全量重建 + 内部 reindex 端点（`POST /api/v1/internal/search/reindex`，仅运维）：遍历 posts bulk 写 `post_search_v{n+1}` → 原子切别名 → 删旧索引
- [ ] TDD（S2 seam，Prior art #13 `TwoLevelCacheIntegrationTest`）：seed DB(posts+post_tags)→index(postId)→断言 ES 文档字段(tags 来自 join、authorName 来自 Feign 桩、status、计数)；status=DELETED 后文档保留；重复 index 幂等不产生副本
- [ ] 编译级快验通过；现有测试不破坏
