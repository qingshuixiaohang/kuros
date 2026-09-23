# se-05: CDC 管道 Canal → RocketMQ → consumer → ES

**What to build:** 帖子的任何变更（发布/编辑/逻辑删/改标签）都能自动同步到 ES 索引——Canal 伪装 slave 订阅 MySQL binlog，经 RocketMQ 投递 FlatMessage，backend 消费者解析出变更的 postId 触发 PostIndexService 回源重组。以 binlog 为唯一事实源，对业务零侵入，无论变更来自应用还是手工改库都能捕获。

**Blocked by:** se-02（canal + mysql binlog + rocketmq 基建）、se-03（PostIndexService 回源组装）

**Status:** ready-for-agent

- [ ] canal instance 配置：订阅 `kuros.posts` + `kuros.post_tags`（filter.regex），`serverMode=rocketMQ` + `flatMessage=true` 投递到 topic（如 `post-cdc`），复用 #11 namesrv/broker
- [ ] `partitionHash=kuros\.posts:id` 让同一 postId 落同一队列，保证同帖变更有序
- [ ] backend 新增 CDC 消费者（Spring Cloud Stream function，复用 #11 binder 配置模式），orderly 消费 + 重试 + 死信（`%DLQ%`）
- [ ] 消费者解析 FlatMessage 取变更 postId 集合（`data[].id` / `pkNames`）→ 对每个 postId 触发 `PostIndexService.index`；`post_tags` 变更据其 `post_id` 列同样触发对应帖子重组
- [ ] 物理 DELETE 事件 → 兜底 delete by _id
- [ ] CDC 断连恢复：Canal 从 binlog position 续传补齐（binlog 保留期内不丢）
- [ ] 消费端单测（可 mock PostIndexService）：FlatMessage JSON → postId 集合 → 触发 index 的组装逻辑；Canal→MQ 传输层不做单元/集成测试（太重太脆，交 se-07 端到端冒烟覆盖）
- [ ] 编译级快验通过；现有测试不破坏
