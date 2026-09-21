# Ticket: 部署冒烟收口 + 学习复盘 + Issue 关闭（Phase B-5）

**父 Issue**：#67（切片 #10：服务拆分——用户域独立微服务）
**依赖**：split-08
**阻塞**：无（切片收口）

## 范围

1. compose 全链验证：8 服务 healthy（mysql/redis/minio/nacos/backend/gateway/user/frontend），健康检查依赖链完整
2. 冒烟脚本升级：
   - `verifyNacosRegistration` 校验三服务（kuros-backend、kuros-gateway、kuros-user）
   - 新增跨服务端到端链路：**经网关登录（打到 user 写 Redis）→ 发帖（打到 backend 读同一 Redis 会话）→ 帖子详情作者昵称经 Feign 回填**
3. 文档更新：README/HANDOFF 注明"本地调试统一走网关 8080；直连 8090 时用户域端点 404 属预期"；CONTEXT.md 若有细节偏差修正
4. 学习复盘 `docs/learning/10-service-split.md`（七段式模板）+ `docs/learning/README.md` 索引更新
5. Issue #67 验收评论（逐项证据）并关闭；PR 合并

## 验收

- [ ] compose 8 服务 healthy；compose config 校验通过
- [ ] 冒烟脚本全过（三服务注册 + 跨服务链路）
- [ ] CI 五 job 全绿
- [ ] 学习复盘产出；Issue #67 关闭

## 备注

- 跨服务链路是"会话共享 + 拆分边界 + 服务间调用"三能力的最终验收大戏
- **复盘按新模式标准（从 #10 起适用）**，除七段式外须含：① STAR 面试故事（问题 → 原方案为何不行 → 方案 → 收益量化）② ≥5 条追问链回答清单（单体为什么不香了？为什么先拆 user？为什么不是一开始就拆？跨库一致性怎么保证？拆的代价是什么？收益怎么验证？）③ 方案对比表（模块化单体 vs 一次性全拆 vs 渐进式拆，含各自适用边界）
