# rp-07: 集成测试 + 性能验证

**What to build:** 用跨切面集成测试证明三个难点真实生效（击穿只重建一次、两级缓存命中回填、pub/sub 跨节点失效、空值哨兵防穿透、cursor 深翻正确），并用实测数据量化收益（击穿保护前后 DB 重建次数、offset 深翻页 vs cursor 同深度耗时），写进学习复盘。

**Blocked by:** rp-01, rp-02, rp-03, rp-04, rp-05, rp-06

**Status:** ready-for-agent

- [ ] 击穿并发集成测试：N 线程打同一过期热帖，断言 DB 重建只发生一次（Testcontainers Redis + H2，prior art `CacheIntegrationTest`）
- [ ] 两级缓存测试：L1/L2 命中与回填、计数解耦（缓存内容后改实时计数，详情返回最新值）
- [ ] pub/sub 失效测试：写路径删 L2 + 广播，订阅者清 L1
- [ ] 空值哨兵测试：不存在 id 第二次不查 DB
- [ ] cursor 测试：Feed + 帖子列表深翻正确性、同分/同排序键去重、HTTP 契约（MockMvc，prior art `KurosBackendApplicationTests`）、offset 端点兼容
- [ ] **性能验证（DoD 硬要求，禁止编造并发数字）**：实测 offset page=1000 vs cursor 同深度耗时对比、击穿保护前后 DB 重建次数对比，真实数据写进 `docs/learning/13`
- [ ] 全量 `mvn test` 全绿（**耗时测试交用户/CI 执行**，AI 只做 `test-compile` 快验）
