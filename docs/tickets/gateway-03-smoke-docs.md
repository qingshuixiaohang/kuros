# Ticket: 冒烟扩展 + 学习复盘 + Issue 关闭

**父 Issue**：#65（切片 #9：Spring Cloud Gateway 统一入口）
**依赖**：gateway-02
**阻塞**：无

## 范围

1. `scripts/compose-smoke.mjs`：`verifyNacosRegistration` 扩展为同时校验 `kuros-backend` 与 `kuros-gateway` 两个服务名（仅多查一个服务名，Q2 决策：不通过网关重复跑全量业务断言）
2. `docs/learning/09-gateway.md`：WebFlux 网关独立容器原因、lb 路由原理、端口策略、踩坑记录（切片惯例，STAR 结构）
3. 用户执行验证：`mvn test`（两工程）→ `docker compose up -d --build` → `node scripts/compose-smoke.mjs`
4. 回写 Issue #65：测试结果、已知限制，关闭 Issue

## 验收

- smoke 全过，Nacos 两个服务实例均 healthy
- 前端走 8080（网关）登录/发帖/上传零改动
- Issue #65 附验证评论关闭

## 备注

- 冒烟脚本对 apiBase 的既有请求（登录/发帖等）此时已天然经过网关（8080 = gateway 正门），无需改断言
