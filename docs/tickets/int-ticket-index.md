# 切片 #11 Ticket 拆解（互动写路径异步化 + RocketMQ）

**父 Issue**：[#69](https://github.com/qingshuixiaohang/kuros/issues/69) · **Spec**：`docs/specs/async-interaction-rocketmq.md` · **ADR**：`docs/adr/0004-async-interaction-rocketmq.md`

| # | Ticket | 阻塞于 | 交付 |
|---|--------|--------|------|
| 1 | [int-01-rocketmq-infra](./int-01-rocketmq-infra.md) | 无 | RocketMQ 依赖 + compose 三服务 + binder 配置 + 最小 ping 管道贯通（11 服务） |
| 2 | [int-02-redis-read-source](./int-02-redis-read-source.md) | 无 | Redis 实时读源（状态/计数 key + Lua 原子回填 + 翻转判定）+ 互动快照读路径 Redis 优先 |
| 3 | [int-03-event-consumer](./int-03-event-consumer.md) | int-01 | InteractionEvent 契约 + 顺序消费端事务内幂等落库 + 刷计数 + 重试/死信 |
| 4 | [int-04-write-path-async](./int-04-write-path-async.md) | int-02、int-03 | 写路径改造：Redis 前置 + 发顺序消息（分区键 postId）+ 发送失败同步降级（不再同步 UPDATE posts） |
| 5 | [int-05-tests-e2e](./int-05-tests-e2e.md) | int-04 | 现有同步精确断言→最终一致轮询 + RocketMQ Testcontainers 端到端 + 幂等/降级/顺序用例 |
| 6 | [int-06-smoke-docs](./int-06-smoke-docs.md) | int-05 | compose 冒烟互动异步链路 + 文档回写 + 学习复盘 + Issue #69 回写 |

## 依赖图（DAG）

```
int-01 ─┐
        ├─→ int-03 ─┐
int-02 ─┴───────────┼─→ int-04 ─→ int-05 ─→ int-06
        (int-02 ────┘  亦直接阻塞 int-04)
```

- **frontier（可立即开始）**：int-01、int-02 无阻塞，可并行起跑。
- int-03 阻塞于 int-01（需 binder 管道）；int-04 阻塞于 int-02 + int-03（读源 + 落库都就位才改写路径）；int-05 阻塞于 int-04；int-06 收口阻塞于 int-05。

## 执行说明

- 每个 ticket 是一个可独立验收的垂直切片，按 frontier 顺序执行。
- **耗时测试纪律**：全量 `mvn test`、RocketMQ Testcontainers 端到端、compose 冒烟**交用户/CI**；AI 只做编译级快验（`test-compile` / `compose config` / `node --check`）。
- 收益表述纪律：只用可验证结构性指标，不编造 TPS/RT 并发数字（CONTEXT.md L170）。
