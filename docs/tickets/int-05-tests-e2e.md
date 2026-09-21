# Ticket: 测试重构 + RocketMQ 端到端集成（切片 #11 · C-1）

**父 Issue**：#69（切片 #11：互动写路径异步化）
**依赖**：int-04
**阻塞**：int-06

## 范围

1. **改造现有同步断言**：`KurosBackendApplicationTests` 中「同步断言精确计数（likeCount 3700→3701、favorite 1200→1201）」在异步化后失效 → 改为**最终一致轮询断言**（await/poll DB 直至收敛或超时）；即时反馈断言改为校验 HTTP 响应体的 Redis 实时值
2. **最终一致测试辅助**：引入轮询/await 工具（优先复用现有测试栈，必要时引入 Awaitility），统一互动异步断言范式
3. **RocketMQ Testcontainers 端到端**：真实 namesrv+broker 容器，验证「点赞 → 发顺序消息 → 消费 → DB 最终一致」全链路；顺序性（同一 postId 的 LIKE→UNLIKE 序列终态正确）
4. **补齐用例**：重复点赞幂等（Redis + DB）、取消对称、消费幂等、降级不丢、游客 GET 快照（未登录返回计数 + liked/favorited=false）
5. **测试隔离**：沿用 `TestDatabases`（唯一 H2 库名工厂）+ Surefire `reuseForks=false` 每类独立 JVM + `@DirtiesContext`；RocketMQ 容器测试单独类，避免与 H2 上下文串味

## 验收

- [ ] 现有互动测试改造为最终一致断言后全绿（**耗时，交用户/CI 执行**）
- [ ] RocketMQ Testcontainers 端到端测试绿（点赞→消费→DB 收敛 + 顺序终态）
- [ ] 降级/幂等/游客快照用例齐绿
- [ ] 全量 `mvn test` 无跨类串味（隔离范式沿用）

## 备注

- 耗时测试（全量 mvn test、Testcontainers RocketMQ 端到端）**交用户/CI**；AI 只做编译级快验（test-compile）
- 断言范式从「同步精确」转「最终一致轮询」是本切片的测试观感变化，须在复盘记录
