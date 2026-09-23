# se-04: 搜索读路径 GET /api/v1/search

**What to build:** 用户能通过 `/api/v1/search` 做中文全文检索——多字段加权匹配、关键词高亮、category/tag/type 过滤、相关性/时间/热度三种排序、search_after 深翻不退化，返回复用 #13 的 `CursorPageResult` 游标契约；ES 不可用时明确降级为"搜索暂不可用"（不静默空、不 500）。

**Blocked by:** se-03（需索引文档 + PostSearchDoc 就位）

**Status:** done

- [x] 新增 `GET /api/v1/search?keyword=&category=&tag=&type=&sort=&cursor=&limit=`
- [x] 查询：`multi_match`(title^3, excerpt^2, content^1) + ik_smart；过滤 `term`(category/type/status=PUBLISHED) + `term`(tags)；高亮返回 title/excerpt/content 片段
- [x] 排序 `sort=relevance|latest|hot`：relevance 按 `_score`、latest 按 publishedAt、hot 按 likeCount/commentCount/publishedAt；三种排序均附加 tie-breaker(postId) 保证 search_after 全序稳定
- [x] search_after 游标：cursor 编码上一页最后一条 sort values（不透明 base64url，呼应 #13 `CursorCodec`）
- [x] 响应复用 #13 `CursorPageResult<PostSearchItem>`（items + nextCursor + hasMore，不带 total）；PostSearchItem = 帖子摘要字段 + highlight(map)
- [x] ES 软依赖降级：捕获 ES 不可用 → 抛 `ServiceUnavailableException` → 503 SERVICE_UNAVAILABLE
- [x] `/api/v1/posts` 的 LIKE keyword 保留不动（未触碰 #13 列表游标契约）
- [x] ES 客户端全走 Spring Data ES（`ElasticsearchOperations` + `NativeQuery`），排序/高亮/multi_match 用原生 ES Java Client 的 `SortOptions`/`Query` DSL 构造
- [x] TDD（S1 seam，Prior art #13 `CursorHttpContractIntegrationTest`）：`SearchHttpContractIntegrationTest`（10 用例）+ `SearchUnavailableIntegrationTest`（降级 1 用例）
- [x] 编译级快验通过；现有测试不破坏

## 实现证据（实测）

**门控测试：11/11 green**（`mvnw -o test -Dtest=SearchHttpContractIntegrationTest,SearchUnavailableIntegrationTest -Dkuros.it.es=true`，BUILD SUCCESS，约 1m57s，Testcontainers 起 redis:7-alpine + kuros-es-ik:9.4.5）。

**10 个契约用例覆盖**：①ik 分词命中隔字中文（"鸣潮攻略"→"鸣潮的攻略详解"，LIKE 做不到）且 status=DELETED 被 filter 排除；②multi_match boost 令 title 命中排在 content 命中前；③高亮片段被 `<mark>` 包裹；④category/type 过滤缩小结果集；⑤空关键词 + tag 过滤退化为 match_all；⑥latest 按 publishedAt 倒序；⑦hot 按 likeCount 倒序；⑧search_after limit=1 翻 3 页不重不漏、hasMore 收敛、末页 nextCursor=null；⑨响应复用 CursorPageResult 契约且无 meta；⑩非法游标 → 400 INVALID_CURSOR（游标解码前置于 ES 调用，不被降级 catch 吞）。降级用例：ES URI 指向死端口 → 503 SERVICE_UNAVAILABLE。

**关键坑（实测发现并修复）**：首跑 9/11 红，全部 `503`——`operations.search` 抛 `search_phase_execution_exception: all shards failed`。根因：`PostSearchDoc.postId` 仅有 `@Id`（映射到 ES `_id` 元字段），Spring Data ES 6.1.1 未在 mapping 里生成可排序的 `postId` 字段；而三种排序都以 postId 作 tie-breaker，**对未映射字段排序 → all shards failed**。修复：给 postId 显式加 `@Field(type = Keyword)`（字节码验证 `MappingBuilder` 的 @Field 分支会生成 keyword 映射），重跑 11/11 green。附带把降级日志从 `e.toString()` 改为打完整堆栈（`log.warn(msg, e)`），否则顶层 "all shards failed" 会掩盖真正失败原因。

**设计要点**：`SearchSort` 枚举 co-locate「ES 排序子句」与「search_after 游标 token 类型」防漂移（relevance/latest 2 位、hot 4 位，逐位还原 Float/Long/String）；`withTrackTotalHits(false)`（CursorPageResult 不带 total）；`withPageable(PageRequest.of(0, size+1))`（from=0 满足 search_after 约束 + 多取一条探测 hasMore）；种子直写 ES（`operations.save`）隔离 DB/Feign，聚焦搜索行为。

**决策偏差**：工单写 `terms`(tags)，实现用 `term` 于 tags(keyword 数组)——ES 对 keyword 数组的 term 查询即「数组包含该值」语义，无需 terms；高亮字段比工单多覆盖 excerpt（title/excerpt/content 三者）。
