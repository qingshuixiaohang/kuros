# rp-07: 集成测试 + 性能验证

**What to build:** 用跨切面集成测试证明三个难点真实生效（击穿只重建一次、两级缓存命中回填、pub/sub 跨节点失效、空值哨兵防穿透、cursor 深翻正确），并用实测数据量化收益（击穿保护前后 DB 重建次数、offset 深翻页 vs cursor 同深度耗时），写进学习复盘。

**Blocked by:** rp-01, rp-02, rp-03, rp-04, rp-05, rp-06

**Status:** done（全量 mvn test BUILD SUCCESS 83/0/0/2skip；perf 基准实跑采集真实数据已回填复盘 §5）

- [x] 击穿并发集成测试：N 线程打同一过期热帖，断言 DB 重建只发生一次（`CacheBreakdownIntegrationTest`，rp-02 已落地，16 线程惊群 rebuild==1）
- [x] 两级缓存测试：L1/L2 命中与回填、计数解耦（`TwoLevelCacheIntegrationTest` + `CacheIntegrationTest`，rp-01 已落地）
- [x] pub/sub 失效测试：写路径删 L2 + 广播，订阅者清 L1（`CacheEvictionPubSubIntegrationTest`，rp-03 已落地）
- [x] 空值哨兵测试：不存在 id 第二次不查 DB（`CacheBreakdownIntegrationTest`，rp-02 已落地）
- [x] cursor 测试：Feed（`FeedCursorIntegrationTest` 5）+ 帖子列表（`PostListCursorIntegrationTest` 6）深翻正确性/同分同排序键去重/offset 兼容；**HTTP 契约（MockMvc）新增 `CursorHttpContractIntegrationTest` 5 用例**（游标信封 data.items/nextCursor/hasMore + meta 不序列化、offset 兼容、非法游标 400 INVALID_CURSOR、关注流会话拦截 401）
- [x] 性能验证 harness（禁止编造数字）：新增 `ReadPathPerfBenchmark`（`@EnabledIfSystemProperty(perf=true)` 门控，默认不进 CI）——实测 offset 多深度 vs cursor 同深度耗时表 + 击穿保护 64→1 重建次数对比，`[PERF]` 前缀 println 真实数据
- [x] **性能数据回填**：已实跑 `mvn test '-Dtest=ReadPathPerfBenchmark' '-Dperf=true' '-Dperf.posts=5000'` BUILD SUCCESS，`[PERF]` 真实数据已回填 `docs/learning/13` §5（击穿 64→1、峰值降 98.4%；offset vs cursor page250 33.5ms vs 3.84ms = 8.73x）
- [x] 全量 `mvn test` 全绿：本地实跑 `Tests run: 83, Failures: 0, Errors: 0, Skipped: 2`（skip = InteractionRocketMQ 需真实 MQ），等价 CI `./mvnw test`

## 执行记录（用户授权 AI 自跑迭代至全绿）

```bash
# 1) 全量后端集成测试（Testcontainers Redis + H2）
cd kuros-backend && ./mvnw.cmd test          # → Tests run: 83, Failures: 0, Errors: 0, Skipped: 2 / BUILD SUCCESS
# 2) 性能基准（采集真实数据）
cd kuros-backend && ./mvnw.cmd test '-Dtest=ReadPathPerfBenchmark' '-Dperf=true' '-Dperf.posts=5000'   # → BUILD SUCCESS
```

- PowerShell 陷阱：`-Dperf.posts=5000` 不加引号会被拆成 `-Dperf` + `.posts=5000`（Unknown lifecycle phase），必须逐参加单引号。
- 实跑结果：全量套件 83/0/0/2skip 全绿；perf 基准 `[PERF]` 输出已回填 `docs/learning/13` §5（击穿 64→1/峰值降 98.4%；offset vs cursor page250 33.5ms vs 3.84ms=8.73x）。
- perf 基准被 `@EnabledIfSystemProperty(perf=true)` 门控，常规 `mvn test`/CI 不会跑它（日志中无 ReadPathPerfBenchmark 记录实证）。
