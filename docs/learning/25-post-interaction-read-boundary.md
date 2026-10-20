# 25. 帖子详情互动计数读取边界

## 本批次收敛的边界

- `CommunityPostService` 不再依赖 `InteractionRedisStore` 和 `InteractionKind`；帖子域定义 `PostInteractionCountReader`，互动域提供 Redis 适配器。
- 端口接受惰性数据库基线供给器，因此 Redis 命中时不加载帖子实体；miss 时仍以数据库冗余列回填。
- 详情缓存仍只包含内容字段，互动计数仍在每次响应组装时读取实时源，遵守 ADR-0006。
- 本次只移除 `post` → `interaction` 的具体实现依赖；写路径和互动侧对帖子实体/仓储的依赖保持原样。

## 验证

- `RedisPostInteractionCountReaderTest`：2/2 通过，覆盖实时值优先、基线惰性以及点赞/收藏基线映射。
- `mvnw -DskipTests test-compile`：通过。
- PR #142 全量 CI：后端 190 条、0 failures、0 errors、21 skipped；用户服务、Gateway、前端与 Compose job 全部通过。
- 后端全量测试运行了 `TwoLevelCacheIntegrationTest` 3/3、`InteractionAsyncIntegrationTest` 5/5、`InteractionBatchStateIntegrationTest` 8/8、`InteractionCountReconcileIntegrationTest` 10/10 和新增适配器单测 2/2。
- 本地 Docker daemon 不可连接；完整容器集成覆盖由 CI 完成。

## 复盘

- 模块反向依赖只需要一个由被依赖方拥有的小端口；Redis 类型和映射留在互动域适配器内。不要为了这条读路径把存储类型移入 shared，也不要借机迁移写路径。
- 依赖边界重构要同时检查 ADR 约束和调用语义。这里数据库基线必须继续惰性，否则详情缓存命中会重新触发帖子 DB 查询。
- Windows JDK 的动态 Mockito agent attach 在本机遇到 loopback pipe 错误。对只需覆盖简单端口映射的小单元测试，轻量 fake 可以移除 agent attach 依赖；容器集成仍由 CI 验证。
- 构造注入变更的验证重点不仅是编译，还要启动真实 Spring 上下文并运行缓存、互动写和状态查询用例；本批次用手动全量 CI 覆盖。
