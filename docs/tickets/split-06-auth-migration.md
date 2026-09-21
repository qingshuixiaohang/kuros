# Ticket: 认证链路迁移至 kuros-user（Phase B-2）

**父 Issue**：#67（切片 #10：服务拆分——用户域独立微服务）
**依赖**：split-05
**阻塞**：split-07

## 范围

1. 认证代码迁入 kuros-user：`AuthController`/`AuthService`（验证码登录、首次登录建号、`/me`）、验证码三件套、`StpInterfaceImpl` 与 RBAC 仓储、`CommunityUser` 实体与仓储
2. SaToken 配置（user 子集白名单：`/api/v1/auth/**` 放行、OPTIONS 放行、`checkLogin` 兜底）+ CSRF 拦截器（复制，规则同 backend 语义）
3. backend 移除上述代码；backend 侧 SaToken 白名单相应收窄（`/api/v1/auth/**` 条目可保留为透传容错，或随路由移除——以实现时测试为准）
4. Gateway 路由：`/api/v1/auth/**` → `lb://kuros-user`（在既有 `/**` → backend 路由之前匹配）
5. kuros-user 测试（TDD 先 RED）：Testcontainers MySQL + Redis 的登录链路测试（验证码→登录→会话写入 Redis→`/me`）；Nacos 注册发现测试

## 验收

- [ ] 经网关登录（`/api/v1/auth/*`）打到 kuros-user 并写共享 Redis、下发同名 Cookie
- [ ] backend 直连 8090 的 `/api/v1/auth/*` 返回 404（端点已迁走）
- [ ] kuros-user 测试全绿；CI 绿

## 备注

- 会话零迁移的关键：两服务同名 Cookie + 相同 `sa-token` 配置 + 同一 Redis（切片 #1 决策的回报）
- CSRF 拦截器排除 `/api/v1/auth/**` 的既有语义原样复制
