# 0002. 用 Sa-Token 和 Redis 管理登录会话与权限

日期：2026-09-20

状态：已接受。继续采用 [ADR 0001](0001-browser-session-cookie.md) 的浏览器 Cookie；会话的服务端存储改用 Redis。

## 背景

早期单体用 Spring Security 过滤器读取 Cookie，再用 MySQL 的 `user_sessions` 和用户表还原登录身份。验证码存在应用进程里，应用重启会丢失。角色也只有代码里定义的几种，业务接口难以按权限单独控制。项目希望先把认证基础打好，再拆分用户服务。

原方案的 MySQL 会话可以被多个实例读取。每个实例在本次请求里建立自己的安全上下文，并不妨碍共享会话。这里改变的是认证框架、会话存储和权限模型。

## 决策

1. Sa-Token 负责建立登录会话、从 Cookie 读取令牌，以及在服务端检查登录和权限。Redis 保存会话、验证码和权限读取所用的数据。验证码设有效期和重发间隔。
2. 角色和权限以 MySQL 中的 RBAC 表为来源。RBAC 的意思是先把用户关联到角色，再把角色关联到权限。例如管理员可以拥有处理举报的权限；接口检查的是这项权限。登录时会把角色和权限同步到 Redis，供后续请求使用。
3. 浏览器仍携带同名 Cookie `KUROS_SESSION`。拆服务后，用户服务登录成功建立会话，内容服务收到发帖请求时，使用一致的 Sa-Token 配置从共享 Redis 查这个会话。登录写入安排在用户数据库事务提交后，避免数据库回滚却留下有效会话。见 [ADR 0003](0003-user-service-split.md)和[登录实现](../../kuros-user/src/main/java/com/kuros/kurosuser/service/AuthService.java)。
4. Cookie 凭据仍配套跨域和 CSRF 校验。服务间调用另有自己的鉴权规则。

## 备选与代价

保留 Spring Security、用 Spring Session 接 Redis 也能共享会话，迁移面更小。本项目选 Sa-Token 来统一会话和权限检查。自包含 JWT 可以减少对会话存储的依赖，若需要提前撤销或立刻更新权限，则要额外设计状态管理。采用 Redis 后，登录与鉴权也依赖 Redis 的可用性。

切换后旧 MySQL 会话表先停用，随后由内容库 V10 迁移移除。实际每次请求的 Redis 或 SQL 调用数取决于登录、角色检查和业务路径，不能按一次 Redis 查询估算整个接口。

## 验证入口

[分布式登录规格](../specs/distributed-session-auth.md)、[Sa-Token 与 RBAC 复盘](../learning/01-satoken-redis-rbac.md)、[安全加固复盘](../learning/15-security-hardening-and-test-honesty.md)。
