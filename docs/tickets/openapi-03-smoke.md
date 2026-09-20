# Ticket: 冒烟验证

**依赖**：ticket-02
**阻塞**：无

## 范围

1. `docker compose up -d --build` 全栈启动
2. 访问 `/swagger-ui/index.html` 验证 Swagger UI 可交互
3. 验证 `/v3/api-docs` 返回完整 API 文档
4. Playwright e2e 测试通过

## 验收

- Swagger UI 可访问
- API 文档包含所有端点
- e2e 测试全绿
