# Compose 用户服务开发 profile 规格

## 问题

全栈 Compose 为 `user` 服务显式注入默认固定开发验证码 `123456`，但未设置 Spring profile。`DevCodeSafetyValidator` 因 active profiles 为空拒绝服务启动，导致全栈部署和 CI 健康检查失败。

## 决策

- `user` Compose 服务默认启用 `dev` profile，与该 Compose 服务的开发验证码设置一致。
- 通过 `USER_SPRING_PROFILES_ACTIVE` 允许调用方显式覆盖 profile。
- 不改 `DevCodeSafetyValidator` 的 fail-fast 规则，不改变空验证码的安全默认值，不把 `dev` profile 应用到其他服务。

## 验收

- 默认 Compose 配置将 `SPRING_PROFILES_ACTIVE=dev` 注入 `user` 容器。
- 显式提供 `USER_SPRING_PROFILES_ACTIVE` 时，Compose 将其传入容器。
- 全栈 Compose 启动中，`user` 服务健康检查通过，现有认证相关冒烟步骤继续执行。
- `DevCodeSafetyValidator` 的现有安全测试保持通过。

## 非目标

- 改变开发验证码、验证码回显或启动安全校验行为。
- 给 backend、gateway 或其他服务添加 Spring profile。
