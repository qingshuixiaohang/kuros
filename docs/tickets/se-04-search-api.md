# se-04: 搜索读路径 GET /api/v1/search

**What to build:** 用户能通过 `/api/v1/search` 做中文全文检索——多字段加权匹配、关键词高亮、category/tag/type 过滤、相关性/时间/热度三种排序、search_after 深翻不退化，返回复用 #13 的 `CursorPageResult` 游标契约；ES 不可用时明确降级为"搜索暂不可用"（不静默空、不 500）。

**Blocked by:** se-03（需索引文档 + PostSearchDoc 就位）

**Status:** ready-for-agent

- [ ] 新增 `GET /api/v1/search?keyword=&category=&tag=&type=&sort=&cursor=&limit=`
- [ ] 查询：`multi_match`(title^3, excerpt^2, content^1) + ik_smart；过滤 `term`(category/type/status=PUBLISHED) + `terms`(tags)；高亮返回 title/content 片段
- [ ] 排序 `sort=relevance|latest|hot`：relevance 按 `_score`、latest 按 publishedAt、hot 按计数；非 relevance 附加 tie-breaker(postId) 保证 search_after 全序稳定
- [ ] search_after 游标：cursor 编码上一页最后一条 sort values（不透明 base64url，呼应 #13 `CursorCodec`）
- [ ] 响应复用 #13 `CursorPageResult<PostSearchItem>`（items + nextCursor + hasMore，不带 total）；PostSearchItem = 帖子摘要字段 + highlight(map)
- [ ] ES 软依赖降级：捕获 ES 不可用 → 返回明确"搜索服务暂不可用"
- [ ] `/api/v1/posts` 的 LIKE keyword 保留不动（不破坏 #13 列表游标契约）
- [ ] ES 客户端优先 Spring Data ES（`ElasticsearchOperations`/`NativeQuery`），高亮/聚合未覆盖的用原生 ES Java Client 补充
- [ ] TDD（S1 seam，Prior art #13 `CursorHttpContractIntegrationTest`）：HTTP 契约测试——ik 分词命中、多字段 boost 排序、高亮片段、过滤、三排序、search_after 深翻、CursorPageResult 契约、ES 不可用降级
- [ ] 编译级快验通过；现有测试不破坏
