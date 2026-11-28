---
status: accepted
---

# 0001. 浏览器登录使用安全 Cookie 会话

登录成功后，服务端通过 `Set-Cookie` 让浏览器保存 `KUROS_SESSION`。下次请求符合 Cookie 规则时，浏览器会自动带上它，后端用它找到登录会话。例如刷新页面后，前端调用当前用户接口，就能恢复登录显示。Cookie 保存的是用于查会话的凭据，服务端会话在 [ADR 0002](0002-distributed-session-satoken-redis.md) 中迁到了 Redis。

## 考虑过的方案

- **由前端脚本保存并发送令牌**：发送时机可以由前端控制，但脚本也能读到令牌。这里选择 `HttpOnly` Cookie，减少令牌直接暴露给脚本的机会。JWT 是令牌格式，Cookie 是浏览器存储和携带凭据的方式，两者可以组合。

## 影响

- 登录、获取当前用户和退出登录需要围绕会话设计统一接口。
- 跨域请求需要前端带凭据，服务端也要允许相应来源。浏览器仍依据 Cookie 的属性判断是否发送。当前会话配置为 `HttpOnly`、`SameSite=Lax`，`Secure` 由部署配置控制，HTTPS 环境应启用。
- 由于浏览器会自动携带会话 Cookie，受保护的写请求还要校验 CSRF 凭据。登录态回答「是谁」，CSRF 校验帮助确认写操作来自能读取并回传相应凭据的页面。

[前端请求封装](../../front/src/lib/api.ts) · [用户服务 Cookie 配置](../../kuros-user/src/main/resources/application.properties)
