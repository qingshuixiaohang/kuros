# Ticket: 冒烟验证

**依赖**：ticket-03
**阻塞**：无

## 范围

1. `docker compose up -d --build` 全栈启动
2. 验证 `/actuator/health` 返回 UP
3. 验证 `/actuator/metrics` 列出自定义指标
4. Playwright e2e 测试通过

## 验收

- Actuator 端点正常响应
- 自定义指标 `posts.published.total` 已注册
- e2e 测试全绿
