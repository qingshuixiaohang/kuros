# Ticket: 互动事件契约 + 顺序消费端幂等落库（切片 #11 · B-1）

**父 Issue**：#69（切片 #11：互动写路径异步化）
**依赖**：int-01
**阻塞**：int-04

## 范围

1. 互动事件模型 `InteractionEvent`：`eventId`(UUID) / `postId`(分区键) / `userId` / `type`(LIKE|UNLIKE|FAVORITE|UNFAVORITE) / `occurredAt`(epoch milli)
2. 消费端 `Consumer<InteractionEvent>`（Spring Cloud Stream function），binding destination = `post-interaction-topic`，**顺序消费**（同一 postId 落同一队列、单线程串行）；替换 int-01 的 ping binding
3. **事务内幂等落库**：
   - LIKE → `if(!existsById){ save(PostLike); incrementLikeCount }`
   - UNLIKE → `if(existsById){ deleteById; decrementLikeCount }`
   - FAVORITE/UNFAVORITE 同理（favorite 计数列）
   - 幂等靠 `existsById` + 复合主键：重复消费/重投不重复计数
4. **重试 + 死信**：`spring.cloud.stream.bindings.<consumer>.consumer.max-attempts` + RocketMQ 原生重试；多次失败进 `%DLQ%{consumerGroup}`；消费组 `interaction-consumer-group`
5. 测试（TDD 先 RED）：消费端单元接缝（直调 Consumer，绕过 MQ 传输）——LIKE/UNLIKE/FAVORITE/UNFAVORITE 落库正确；同一事件消费两次幂等（关系一行 + 计数只 +1）；LIKE→UNLIKE 序列终态正确

## 验收

- [ ] `InteractionEvent` 契约就位；消费端 binding 指向 `post-interaction-topic` 且顺序消费配置生效
- [ ] 直调 Consumer：四类事件幂等落库 + 刷对应计数列（消费端单元测试全绿）
- [ ] 重复消费同一事件：DB 关系一行、计数只变一次（幂等断言）
- [ ] `max-attempts` + 死信配置就位（配置级验证；真实重投端到端交 int-05/CI）

## 备注

- 消费端与写路径（int-04）解耦：本 ticket 只保证「给我事件，我幂等落库」，事件由谁发不管
- 顺序性依赖 binder 的分区键（postId）；精确配置以 SCA binder 文档为准，int-01 已打通管道
