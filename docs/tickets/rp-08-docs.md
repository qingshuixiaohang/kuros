# rp-08: 文档收口 + Issue 回写 + PR

**What to build:** 切片 #13 收口——产出七段式学习复盘（含 STAR 面试故事、≥5 条追问链、方案对比表、实测数据），回写 Issue #73 测试结果与已知限制，提交 PR 并关联 Issue。

**Blocked by:** rp-07

**Status:** ready-for-agent

- [ ] `docs/learning/13-read-path-hardening.md` 七段式复盘：① 架构迁移全景 ② 关键难点解析（击穿/穿透/雪崩区别、互斥锁 vs 逻辑过期、offset vs keyset、两级缓存一致性、计数解耦）③ 简历 STAR ④ 原理详解 ⑤ 面试八股 ⑥ 技术选型对比表 ⑦ 面试叙事模板（30 秒 + 2 分钟）
- [ ] 复盘内嵌 rp-07 的实测性能数据（DB 重建次数对比、offset vs cursor 耗时对比）
- [ ] `docs/learning/README.md` 表格补 #13 行
- [ ] Issue #73 回写：测试结果、已知限制（pub/sub 丢消息兜底、空值哨兵窗口、游标不支持跳页）、关联 PR
- [ ] 提交 PR（标题 `feat(slice-13): 读路径加固——两级缓存 + 互斥锁防击穿 + 游标分页`），关联 Issue #73
- [ ] CONTEXT.md / ADR 0006 / spec 已在 grill 阶段落库，确认无遗漏
