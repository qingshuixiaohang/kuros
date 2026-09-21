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

- [x] 经网关登录（`/api/v1/auth/*`）打到 kuros-user 并写共享 Redis、下发同名 Cookie（静态+编译级闭环：网关 `kuros-user-auth` 路由 `order(-1)` 显式优先于 backend `/**`，双桩集成测试证命中；kuros-user 登录链路集成测试断言会话/角色/权限 Redis key 与同名 Cookie；端到端运行时验证待 CI 冒烟）
- [x] backend 直连 8090 的 `/api/v1/auth/*` 返回 404（端点已迁走）（新增测试编码化：POST /login 与 GET /me 均断言 404，依赖 SaToken notMatch + CSRF exclude 放行条目刻意保留）
- [ ] kuros-user 测试全绿；CI 绿（AI 侧已过：三工程 test-compile、compose config、node --check；全量测试与 CI 待触发）

## 备注

- 会话零迁移的关键：两服务同名 Cookie + 相同 `sa-token` 配置 + 同一 Redis（切片 #1 决策的回报）
- CSRF 拦截器排除 `/api/v1/auth/**` 的既有语义原样复制
- backend 侧白名单条目刻意保留（SaToken notMatch + CSRF exclude）：直连 8090 的 auth 请求落到"无 handler" → 404，而非先被拦成 401/403
- ⚠️ 窗口期限制（split-06~split-08）：非种子手机号首次登录只在 kuros_user 建号，backend 内容域
  （发帖/评论/个人中心）按登录 ID 查本库 users 会 404/403——等 split-08 Feign 回填用户域才能收口。
  compose-smoke / session-persistence-check 因此改用两侧共有种子号（13800000002 / 13800000003）验证
- 验证码端点的 Sentinel QPS 限流规则（`api-auth-code`）随迁移删除：kuros-user 暂未引入 Sentinel
  （Q15-A 决策），当前认证端点仅剩 Redis 60s/手机号节流
