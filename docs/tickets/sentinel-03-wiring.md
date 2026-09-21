# Ticket: SaTokenConfigure 集成 + 可选注入

**依赖**：ticket-02
**阻塞**：ticket-04

## 范围

1. `SaTokenConfigure` 构造函数使用 `ObjectProvider<SentinelRateLimitInterceptor>` 可选注入
2. `addInterceptors()` 中 null check 后注册拦截器，order=-1（鉴权之前执行）
3. 拦截路径 `/api/**`

## 验收

- Sentinel 开启时拦截器正常注册
- Sentinel 关闭时（app.sentinel.enabled=false）SaTokenConfigure 启动不报错
