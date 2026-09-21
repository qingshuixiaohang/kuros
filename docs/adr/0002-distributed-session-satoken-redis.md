# 0002. 分布式会话与鉴权：SaToken + Redis 替代 Spring Security

日期：2026-09-20

状态：已接受，取代 [ADR 0001](0001-browser-session-cookie.md) 中"不引入 Redis"的约束

## 背景与问题

ADR 0001 和 CONTEXT.md 2026-09-16 决策确立了单体基线：Cookie 会话、Spring Security 过滤器链、Session 存 MySQL `user_sessions` 表、验证码存 ConcurrentHashMap。这在 MVP 阶段是合理的，但随着项目向微服务演进（参照犬小哈《Spring Cloud Alibaba 小哈书》），当前方案暴露出以下结构性问题：

1. **每次认证请求查两次 DB**：`SessionAuthenticationFilter` 对每个携带 Cookie 的请求执行 `findByTokenHash` + `findById`，高并发下数据库成为瓶颈。
2. **会话无法跨实例共享**：虽然 Session 存在 MySQL（重启不丢），但多实例部署时每个实例的 `SecurityContextHolder` 是独立的，无法水平扩展。
3. **验证码重启丢失**：`ConcurrentHashMap` 存储，服务重启后所有未使用的验证码失效。
4. **角色硬编码**：`UserRole` 枚举只有 USER/ADMIN，无动态权限配置能力，无 RBAC 表。
5. **鉴权逻辑耦合单体**：`SecurityConfig` 的路由拦截、CSRF、CORS 全部绑定 Spring Security，拆微服务后每个服务都要复制一份。
6. **Spring Security 重量级**：对于 Cookie + Token 的简单认证场景，Spring Security 的过滤器链过于复杂，学习成本和调试成本高。

## 决策

引入 **SaToken + Redis** 完全替代 Spring Security 的会话管理和路由拦截，同时引入 **RBAC 权限模型**：

1. **SaToken 替代 Spring Security**：
   - 移除 `spring-boot-starter-security` 依赖和 `SecurityConfig`。
   - 使用 SaToken 的 `SaInterceptor` 实现路由拦截和注解鉴权。
   - CORS 改用 Spring 的 `WebMvcConfigurer.addCorsMappings()` 独立配置。
   - CSRF 保持双重提交 Cookie 模式，用 SaToken 拦截器自实现（约 20 行）。

2. **Redis 存储会话和权限**：
   - SaToken 的 Token 存储后端改为 Redis（`sa-token-redis-jackson`）。
   - 用户角色和权限集合登录后同步到 Redis，`StpInterface` 从 Redis 读取。
   - 验证码从 ConcurrentHashMap 迁移到 Redis（带 TTL）。
   - 开发环境 Redis 通过 `compose.yml` 提供；测试环境使用 Testcontainers。

3. **RBAC 四张表**：
   - `sys_role`（角色表）、`sys_permission`（权限表）、`sys_user_role`（用户角色关联）、`sys_role_permission`（角色权限关联）。
   - `CommunityUser.role` 枚举字段保留为冗余快捷字段，RBAC 表为权威来源。
   - 登录时从 RBAC 表查出角色后同步写入 `users.role` 和 Redis。

4. **验证码异步化**：
   - `SmsSender` 接口抽象，dev 环境走 `DevSmsSender`（日志输出），生产环境预留 `AliyunSmsSender`。
   - 自定义线程池异步发送。
   - Redis 存储验证码 + TTL + 频率限制（同手机号 60s 内不可重复发送）。

5. **Token 传递保持 Cookie 模式**：
   - Cookie 名保持 `KUROS_SESSION`（前端零改动）。
   - SaToken 配置 `is-read-cookie: true`、`is-read-header: false`。
   - Next.js SSR 场景下 Cookie 自动透传，无需额外处理。

6. **`user_sessions` 表保留但不再写入**：
   - 代码不再依赖该表，但不执行 DROP（避免不可逆迁移）。
   - 后续微服务拆分稳定后再清理。

## 备选方案

- **Spring Security + Redis Session（spring-session-data-redis）**：保留 Spring Security，只把 Session 存储从 MySQL 换成 Redis。改动最小，但简历上写不出"SaToken + RBAC"的故事，且 Spring Security 的复杂度不降反升（要同时理解 Security 和 SaToken 的概念）。
- **JWT 无状态 Token**：不需要 Redis 存 Session，但无法主动踢人下线、无法实时撤销权限、Token 续签复杂。小哈书明确选择 SaToken + Redis 而非 JWT。
- **Spring Security + SaToken 共存**：SaToken 只管 Token，Security 继续管 CORS/CSRF。面试时会被追问"为什么两个都用"，架构不干净。

## 影响

- **前端**：几乎零改动。Cookie 名不变，CSRF 逻辑不变，`api.ts` 的 `credentials: "include"` 和 `XSRF-TOKEN` 保持原样。
- **后端**：`SecurityConfig` 删除，`SessionAuthenticationFilter` 删除，`AuthService` 重构为使用 SaToken API，新增 RBAC 表迁移和 `StpInterfaceImpl`。
- **测试**：37 个测试的认证辅助方法需要适配（`login()` 改为触发 SaToken 登录），业务断言不变。Testcontainers 提供测试 Redis。
- **部署**：`compose.yml` 新增 Redis 服务。
- **API 契约**：`/api/v1/auth/*` 端点路径和响应格式不变，前端无感知。
- **性能**：认证请求从 2 次 DB 查询降为 1 次 Redis 查询（O(1)），为后续水平扩展打基础。

## 对应小哈书章节

- 第五章：SaToken + Redis 登录、RBAC 权限模型、自定义线程池、验证码异步发送
- 第六章 6.1：Nacos 前置准备（本 ADR 不引入 Nacos，留到下一步）

## 简历产出

> 针对单机 Session 每次认证查两次 DB、无法水平扩展的问题，引入 SaToken + Redis 实现分布式会话管理；设计 RBAC 权限模型（四张表 + StpInterface + Redis 权限缓存），支持动态角色配置；验证码改为异步线程池 + Redis TTL + 频率限制，短信通道通过策略模式可扩展；完全替代 Spring Security，认证请求从 2 次 DB 查询降为 1 次 Redis O(1) 查询。
