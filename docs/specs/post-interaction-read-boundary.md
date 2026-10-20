# 帖子详情互动计数读取边界

## 用户故事

作为维护者，我希望帖子详情通过帖子域拥有的读取端口取得实时互动计数，以便帖子应用服务不再依赖互动域的 Redis 实现，同时保持现有详情响应和计数一致性。

## 范围

- 将 `CommunityPostService` 对 `InteractionRedisStore` / `InteractionKind` 的直接依赖改为帖子域定义、互动域实现的计数读取端口。
- 端口提供点赞和收藏两个实时计数；当且仅当 Redis 对应计数缺失时，惰性读取 `CommunityPost.likeCount` 或 `favoriteCount` 作为基线。
- 保持帖子详情组装方式、公开 API、DTO、缓存字段及 Redis key/TTL 语义不变。
- 保持 `PostInteractionService` 的帖子存在/已发布校验和当前用户互动状态行为不变。

## 明确不做

- 不迁移写路径、互动关系仓储、消息投影、计数校准 job 或定时任务。
- 不改 `PostInteractionResponse`、帖子详情响应或任何 HTTP 路由。
- 不改 schema、Flyway、Redis key/TTL、缓存配置或计数最终一致语义。
- 不尝试拆分整个 post/interaction 模块，只消除详情读路径的反向具体实现依赖。

## 行为规格

1. 帖子详情内容缓存命中时，仍不查询帖子实体；Redis 计数命中时，也不执行计数基线供给器。
2. 点赞、收藏计数分别从实时 Redis 源读取；Redis 命中值进入详情响应。
3. 某计数 Redis key 缺失时，仅执行该计数对应的惰性基线供给器，并按现有 `InteractionRedisStore.readCount` 规则回填。
4. 缓存内容保持只含帖子内容字段，不含 likeCount/favoriteCount；详情每次请求仍叠加实时计数。
5. 公开 API、响应字段及用户可见的计数结果语义保持不变。
6. 主代码依赖方向：`post` 不导入 `interaction`；互动域可以依赖帖子实体和仓储以完成写校验、投影及校准。

## 验收标准

- [x] 端口由帖子域拥有，互动域实现它；`CommunityPostService` 中没有互动包导入。
- [x] 单元测试覆盖两个计数的 Redis 命中及惰性基线未调用。
- [x] 单元测试覆盖 Redis 缺失后使用对应基线，并验证另一项计数的基线互不串用。
- [ ] 详情集成测试覆盖响应仍返回实时计数，详情缓存 DTO 不含计数字段（已有 `TwoLevelCacheIntegrationTest` 锁定该行为，待 CI 复跑）。
- [ ] 现有互动写入、状态查询、投影、计数校准定向测试通过。
- [x] `mvnw test-compile` 通过；适配器定向单测 2/2 通过。
- [x] `post` 主代码无 `com.kuros.kurosbackend.interaction` 导入。

## 验证计划

- TDD：先为计数端口/适配器补单测，确认 RED；实现后确认 GREEN。
- 后端：`./mvnw test-compile` 与计数读取、帖子详情、互动写路径相关定向测试。
- Blast radius：搜索 `CommunityPostService`、`InteractionRedisStore`、`readCount`、详情缓存 DTO 与所有详情测试调用方，确认行为和构造注入无遗漏。
