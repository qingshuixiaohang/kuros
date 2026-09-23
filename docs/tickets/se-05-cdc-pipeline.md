# se-05: CDC 管道 Canal → RocketMQ → consumer → ES

**What to build:** 帖子的任何变更（发布/编辑/逻辑删/改标签）都能自动同步到 ES 索引——Canal 伪装 slave 订阅 MySQL binlog，经 RocketMQ 投递 FlatMessage，backend 消费者解析出变更的 postId 触发 PostIndexService 回源重组。以 binlog 为唯一事实源，对业务零侵入，无论变更来自应用还是手工改库都能捕获。

**Blocked by:** se-02（canal + mysql binlog + rocketmq 基建）、se-03（PostIndexService 回源组装）

**Status:** done

- [x] canal instance 配置：订阅 `kuros.posts` + `kuros.post_tags`（filter.regex），`serverMode=rocketMQ` + `flatMessage=true` 投递到 topic（`kuros-post-cdc`），复用 #11 namesrv/broker —— **已在 se-02 的 compose canal-server env 落地**（`canal.instance.filter.regex=kuros\.posts,kuros\.post_tags`、`canal.mq.flatMessage=true`、`canal.mq.topic=kuros-post-cdc`）
- [x] `partitionHash=kuros\.posts:id,kuros\.post_tags:post_id` 让同一 postId 落同一队列，保证同帖变更有序 —— **se-02 compose 已配**（post_tags 按 post_id hash，同帖的标签变更也归同一队列）
- [x] backend 新增 CDC 消费者（Spring Cloud Stream function，复用 #11 binder 配置模式），orderly 消费 + 重试 + 死信（`%DLQ%`）
- [x] 消费者解析 FlatMessage 取变更 postId 集合（`data[].id` / post_tags 的 `post_id`）→ 对每个 postId 触发 `PostIndexService.index`；`post_tags` 变更据其 `post_id` 列同样触发对应帖子重组
- [x] 物理 DELETE 事件 → 兜底 delete by _id（仅 `posts` 表 DELETE；post_tags 的 DELETE 是解绑标签→仍 index 重组，不删文档）
- [x] CDC 断连恢复：Canal 从 binlog position 续传补齐 —— canal-server 内置位点持久化（se-02 部署层），backend 消费端 index 幂等（_id 覆盖写），重放不产生副本
- [x] 消费端单测（mock PostIndexService）：`PostCdcHandlerTest` 8 用例覆盖 FlatMessage JSON→postId 集合→index/delete 分发；Canal→MQ 传输层交 se-07 端到端冒烟
- [x] 编译级快验通过；现有测试不破坏（全量主套件 110 run / 0 fail / 19 skipped-gated，BUILD SUCCESS）

## 实现证据（实测）

**新增类（`search.cdc` 子包，对齐 #11 `interaction.event` 范式）**：
- `FlatMessage`（record + `@JsonIgnoreProperties(ignoreUnknown=true)`）：只绑定 `database/table/type/data`（`data` 为 `List<Map<String,String>>`，binlog 值全 String），忽略 id/pkNames/isDdl/es/ts/sql/sqlType/old。
- `PostCdcHandler`（@Component，纯逻辑翻译层）：`posts`→id 列、`post_tags`→post_id 列；`LinkedHashSet` 去重；`posts` 物理 DELETE→`delete`，其余（posts 增改含逻辑删、post_tags 任何变更）→`index` 回源重组；空批次（DDL）/非目标表忽略。
- `PostCdcStreamConfig`（@Configuration + `@ConditionalOnProperty(app.search.cdc.enabled=true)`）：`Consumer<FlatMessage> postCdcConsumer = handler::handle`，镜像 #11 `InteractionStreamConfig` 的门控防御（测试环境 bean 不装配→binder 不连 broker）。

**配置**：`application.properties` 加 `app.search.cdc.enabled`（默认 true）+ `function.definition=interactionConsumer;postCdcConsumer`（分号多函数）+ CDC 绑定（destination=`kuros-post-cdc` 对齐 canal.mq.topic、group=`post-cdc-consumer-group`、max-attempts=3、orderly=true）；`application-test.properties` 加 `app.search.cdc.enabled=false`（definition 已置空）；compose backend env 加 `APP_SEARCH_CDC_ENABLED`。

**测试**：`PostCdcHandlerTest`（非门控、进主套件）**8/8 green**（Mockito mock PostIndexService + Jackson2 解析真实 canal JSON）——覆盖 posts INSERT/UPDATE(逻辑删)/物理DELETE、post_tags INSERT/DELETE(解绑不删文档)、同批次去重、DDL 空批次、非目标表。

**不破坏现有测试的核查**：全部 @SpringBootTest 均用 `@ActiveProfiles("test")`（definition 空 + async/cdc 双关）→ 无 stream 绑定、不连 broker；唯一显式开 broker 的 `InteractionRocketMQIntegrationTest`（门控）用 @DynamicPropertySource 把 definition 固定为仅 `interactionConsumer`、不覆盖 cdc 开关（继承 test 的 false），故 postCdcConsumer bean 不装配——function.definition 改动只作用于默认 profile（compose/prod，两开关 true + 真实 broker）。

**未覆盖（明确留口）**：Canal→RocketMQ→consumer→ES 的**真实传输链路**不在本单测范围（太重太脆），交 se-07 compose 端到端冒烟（发帖→轮询 /api/v1/search 命中）验证。
