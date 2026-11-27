# 0004. 用 Redis 即时反馈互动，用 RocketMQ 异步落库

日期：2026-09-21

状态：已接受

## 背景

假设 100 个用户同时给同一篇帖子点赞。每个人对应不同的点赞关系，但数据库里的帖子点赞数都在同一行。过去每个 HTTP 请求都要等自己的数据库事务提交，热点帖会让这些更新争同一行锁。同一用户的防重锁挡不住不同用户之间的竞争。

## 决策

1. **HTTP 请求阶段。** 验证帖子已发布，按「用户 + 帖子 + 互动类型」加锁，在 Redis 更新点赞或收藏的状态和计数。部分键缺失时会从数据库读取基线。
2. **写入 MySQL。** 异步开启并投递成功时，HTTP 请求可以返回 Redis 快照。RocketMQ 按帖子 ID 选队列，同队列顺序消费。消费者在一个事务里更新用户与帖子的关系及帖子上的计数。关系已存在的点赞消息重复处理时，计数不会再次增加。
3. **同步路径。** 异步关闭，或发布器返回投递失败时，请求会在锁内同步执行同一个落库过程，并在提交后释放锁。它需要等待数据库。异步路径的消息重试和死信处理仍要单独关注。
4. **读取与修复。** 详情响应按 [ADR 0009](0009-post-interaction-read-boundary.md) 读取实时计数。定期校准用于修补计数偏差，具体来源和并发保护见[计数校准复盘](../learning/24-periodic-interaction-count-reconciliation.md)。

## 备选与代价

同步落库路径较短，提交后才成功返回，适合低负载或降级。让消息并发消费可以缓冲请求流量，但同帖仍可能并发更新同一行。按帖入队把同一条正常异步写路径串行化，同时增加队列积压、重试和数据延迟的运维成本。分到同一队列的不同帖子也会排队，其他写路径或同步降级仍可能争用数据库行锁。

Redis 更新与 MQ 投递跨两个系统。同步分支处理已识别的投递失败，进程中断、投递结果不确定、重试与后续反向操作交错等场景需要分别验证。计数修补也不能代替补齐丢失的互动关系。

## 如何验证

正常热点写入用不同账号操作同一篇帖。分别看接口延迟、等待消费者落完的时间，以及最终关系数和计数。重复消费、MQ 不可用单独验证。延迟或吞吐收益以相同负载的同步与异步对照为准。

[互动写入实现](../../kuros-backend/src/main/java/com/kuros/kurosbackend/interaction/service/PostInteractionService.java)、[落库实现](../../kuros-backend/src/main/java/com/kuros/kurosbackend/interaction/service/InteractionProjectionService.java)、[实施规格](../specs/async-interaction-rocketmq.md)、[实现复盘](../learning/11-interaction-async-rocketmq.md)。
