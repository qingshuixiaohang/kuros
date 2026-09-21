# Ticket: 冒烟扩展 + 学习复盘收尾

**父 Issue**：#64（切片 #8：Nacos 服务注册发现 + 配置中心）
**依赖**：nacos-02
**阻塞**：无（切片终点）

## 范围

1. `compose-smoke.mjs` 扩展：
   - Nacos 控制台可达性检查
   - 通过 Nacos OpenAPI 查询 `kuros-backend` 注册实例（服务发现端到端验证）
2. `docs/learning/` 学习复盘文档（切片惯例）：
   - 注册发现原理：临时实例心跳机制 vs 永久实例
   - 配置中心原理：长轮询（Long Polling）29.5s 设计
   - 分层配置语义：`spring.config.import` 优先级与 optional 前缀
   - SCA 版本兼容性赌注的实际结果记录
3. 关闭 Issue #64，评论附验证结果（mvn test / compose smoke / Nacos 控制台截图说明）

## 验收

- `docker compose up -d --build` + `compose-smoke.mjs` 全通过
- Playwright e2e 68/68 不回归
- 学习复盘文档已产出
- Issue #64 已关闭
