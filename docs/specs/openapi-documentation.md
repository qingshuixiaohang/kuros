# Spec: SpringDoc OpenAPI 自动生成交互式 API 文档

## 概述

引入 springdoc-openapi，自动生成 Swagger UI 交互式 API 文档，方便前端对接和调试。

## 技术方案

- 库：`springdoc-openapi-starter-webmvc-ui`（支持 Jakarta EE）
- 路径：`/swagger-ui.html` + `/v3/api-docs`
- 安全：仅 dev/test 环境启用（`springdoc.api-docs.enabled`）
- Controller 添加 `@Tag`、`@Operation` 注解增强文档可读性

## 不做的事

1. 不做生产环境暴露（安全风险）
2. 不为所有方法加注解（只为主要 Controller 加 Tag）
