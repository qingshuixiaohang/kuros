# rp-08: 文档收口 + Issue 回写 + PR

**What to build:** 切片 #13 收口——产出七段式学习复盘（含 STAR 面试故事、≥5 条追问链、方案对比表、实测数据），回写 Issue #73 测试结果与已知限制，提交 PR 并关联 Issue。

**Blocked by:** rp-07

**Status:** in-progress（复盘/README 已写 + code-review 已过大半；待用户 perf 数据回填 + 全量 mvn test 绿后收口 Issue/PR）

- [x] `docs/learning/13-read-path-hardening.md` 七段式复盘：① 架构迁移全景 ② 关键难点解析（击穿/穿透/雪崩区别、互斥锁 vs 逻辑过期、offset vs keyset、两级缓存一致性、计数解耦）③ 简历 STAR ④ 原理详解 ⑤ 面试八股 ⑥ 技术选型对比表 ⑦ 面试叙事模板（30 秒 + 2 分钟）
- [x] 复盘内嵌 rp-07 的实测性能数据（DB 重建次数对比、offset vs cursor 耗时对比）——§5 已回填真实数据（击穿 64→1/峰值降 98.4%；page250 33.5ms vs 3.84ms=8.73x）+ 全量套件 83/0/0/2skip 结果
- [x] `docs/learning/README.md` 表格补 #13 行
- [ ] Issue #73 回写：测试结果、已知限制（pub/sub 丢消息兜底、空值哨兵窗口、游标不支持跳页）、关联 PR——待全量 mvn test 绿 + perf 数据后回写
- [ ] 提交 PR（标题 `feat(slice-13): 读路径加固——两级缓存 + 互斥锁防击穿 + 游标分页`），关联 Issue #73——待用户确认 commit
- [x] CONTEXT.md / ADR 0006 / spec 已在 grill 阶段落库，确认无遗漏

## code-review 结论（s13-review）

- 委派 CodeReview 子代理审查切片 #13 未提交改动：**无 Blocker/High**，并发正确性/游标全序/计数解耦/缓存一致性/空指针边界/前端竞态/API 兼容均 ✅。总体结论“可提交”。
- 2 条 SHOULD FIX/CONSIDER（evict 时序）已采纳修复：`PostPublishingService.update`/`delete` 将 `twoLevelCache.evict` 后移到鉴权/校验之后——避免不存在/未授权请求事务回滚时仍白花一次跨节点失效广播（两级缓存后 evict = Redis DEL + pub/sub，成本远高于旧 L1-only）；update 仍保证 evict 先于末尾 findPublishedById。`test-compile` EXIT=0。
