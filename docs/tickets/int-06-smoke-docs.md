# Ticket: 冒烟 + 文档回写 + 学习复盘（切片 #11 · C-2 收口）

**父 Issue**：#69（切片 #11：互动写路径异步化）
**依赖**：int-05
**阻塞**：无（切片收口）

## 范围

1. **compose 冒烟**：`scripts/compose-smoke.mjs` 增补互动异步链路验证——11 服务 healthy（含 rocketmq-namesrv/broker/dashboard）→ 经网关登录 → 点赞热帖 → 断言 HTTP 即时反馈（Redis 实时 liked+计数）→ 轮询/等待消费落库后断言 DB 最终一致；重启复用路径不回归
2. **文档回写**：`HANDOFF.md`（切片 #11 状态 + 下一步 #12 Feed 流）、`CONTEXT.md`（如实现期有新决策）、PR 描述勾选各 int 工单 + 补冒烟证据行
3. **学习复盘** `docs/learning/11-*.md`（七段式）+ STAR 面试故事 + ≥5 条追问链回答清单 + 方案对比表（含适用边界，即 Spec ② 节）+ 真实数据（结构性验证证据；性能数字留可选压测）
4. **Issue #69 回写**：逐项验收标准对齐证据 + 已知限制（Redis/DB 短暂不一致、commentCount 显式排除留 #13）+ 关联 PR；关闭 Issue（**需用户确认**，重大不可逆动作）

## 验收

- [ ] compose 冒烟脚本覆盖互动异步链路（即时反馈 + 最终一致），**耗时执行交用户/CI**
- [ ] `docs/learning/11-*.md` 七段式 + STAR + ≥5 追问链 + 方案对比表齐全
- [ ] HANDOFF/PR 文档回写完成，各 int 工单勾选 + 证据行
- [ ] Issue #69 验收评论逐项对齐证据（关闭动作待用户确认）

## 备注

- 遵循用户行为规则：耗时冒烟/全量测试交用户/CI；AI 只做编译级快验（compose config / node --check / test-compile）
- 收益表述纪律：只用可验证结构性指标，不编造 TPS/RT 并发数字
