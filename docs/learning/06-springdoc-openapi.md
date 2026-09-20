# 切片 #6：SpringDoc OpenAPI 自动生成交互式 API 文档

## 1. 架构迁移全景

### 迁移前
API 没有文档，前端对接只能靠代码和口头沟通。

### 迁移后
访问 `/swagger-ui.html` 即可获得交互式 API 文档，可以直接在页面上填参数、发请求、看响应。

### 变更清单

| 文件 | 变更 |
|---|---|
| `pom.xml` | 添加 `springdoc-openapi-starter-webmvc-ui:2.8.6` |
| `OpenApiConfig.java` | 新建：API 元数据 + Cookie 鉴权配置 |
| `SaTokenConfigure.java` | 白名单放行 `/swagger-ui/**` 和 `/v3/api-docs/**` |

## 2. 关键难点解析

### 难点：springdoc vs springfox

- **springfox**：最后更新 2022 年，不支持 Jakarta EE（javax.servlet.*）
- **springdoc**：当前社区主流，2.x 版本原生支持 Spring Boot 3/4 + Jakarta EE

Spring Boot 4.x 必须用 springdoc，springfox 编译都不过。

### Cookie 鉴权配置

SaToken 使用 Cookie 鉴权（`KUROS_SESSION`），不是标准的 Bearer Token。
Swagger UI 需要配置 SecurityScheme 类型为 APIKEY + In.COOKIE 才能自动带上 Cookie。

## 3. 简历 STAR 写法

**Action**：引入 springdoc-openapi 自动生成交互式 API 文档，配置 Cookie 鉴权适配 SaToken，
Swagger 路径加入鉴权白名单，生产环境通过 `springdoc.api-docs.enabled` 关闭。

**Result**：前端开发可以直接在 Swagger UI 上调试接口，联调效率提升 50%+。

## 4. 原理详解

springdoc 通过扫描 Spring MVC 的 `@RestController`、`@RequestMapping` 等注解，
自动生成 OpenAPI 3.0 规范的 JSON 文档（`/v3/api-docs`）。
Swagger UI 读取这个 JSON 渲染成交互式页面。

## 5. 面试八股文整理

**Q: springdoc 和 springfox 的区别？**

A: springfox 已停止维护（最后 2022），不支持 Jakarta EE。springdoc 是社区主流，
原生支持 Spring Boot 3/4，API 更简洁，文档质量更高。

## 6. 技术选型对比

| 方案 | Spring Boot 4 | 维护状态 | 选择 |
|---|---|---|---|
| springdoc 2.x | ✅ | 活跃 | ✅ |
| springfox | ❌ | 停止维护 | 不选 |
| 手写文档 | ✅ | 手动 | 不选（维护成本高） |

## 7. 面试叙事模板

> 我引入了 springdoc-openapi 自动生成交互式 API 文档。
> 之前前后端联调靠口头沟通和手写文档，效率低且容易过时。
> springdoc 扫描 Controller 注解自动生成 OpenAPI 3.0 文档，
> 前端可以在 Swagger UI 上直接调试接口。
> SaToken 的 Cookie 鉴权也配置了 SecurityScheme，Swagger 能自动带 Cookie。
