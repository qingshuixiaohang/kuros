# Ticket: SpringDoc OpenAPI 依赖 + OpenApiConfig 配置

**依赖**：无
**阻塞**：ticket-02

## 范围

1. `pom.xml` 添加 `springdoc-openapi-starter-webmvc-ui:2.8.6` 依赖
2. 新建 `config/OpenApiConfig.java`：API 元数据（标题、描述、版本）+ Cookie 鉴权 SecurityScheme
3. 使用 `components.addSecuritySchemes()` 注册 Cookie 认证方式

## 验收

- `mvn compile` 成功
- `/v3/api-docs` 端点返回 OpenAPI JSON
