# rp-02: 互斥锁防击穿 + 空值哨兵

**What to build:** 热帖缓存过期瞬间，大量并发请求只有一个穿透到 DB 重建缓存，其余请求短暂等待后读到重建好的缓存；查询一个不存在的帖子时，第二次起被空值哨兵拦截、不再打 DB。

**Blocked by:** rp-01（需两级缓存读组件就位）

**Status:** ready-for-agent

- [ ] L2 miss → DB 重建前，用 #5 `DistributedLock` 抢 `lock:postDetail:{postId}`（TTL 3s）
- [ ] **双重检查**：抢到锁后先重读 L2（可能已被其他线程重建），命中直接返回不查 DB
- [ ] **拿不到锁**：自旋重读 L2（50ms × 3 次）；仍 miss 则降级直查 DB（不无限等待，Redis 故障不阻断读）
- [ ] 重建成功回填 L2 + L1 后释放锁（Lua 原子释放）
- [ ] **空值哨兵**：DB 也查无结果时，L2 写入 `__NULL__` 哨兵（TTL 30s）；读路径命中哨兵直接抛 `ResourceNotFoundException`，不查 DB
- [ ] 测试（TDD 先行）：N 个并发线程打同一未缓存热帖，断言 DB 重建只发生一次（计数型 repository/查询计数器），其余线程拿到同一结果；查不存在 id 两次，断言第二次不查 DB 且抛 `ResourceNotFoundException`
- [ ] 编译级快验通过；现有测试不破坏
