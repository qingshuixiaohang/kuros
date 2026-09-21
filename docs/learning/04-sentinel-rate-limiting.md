# 切片 #4：Sentinel 基础限流保护 API

## 1. 架构迁移全景

### 迁移前
```
请求 → [无保护] → SaInterceptor(鉴权) → CsrfInterceptor → Controller → Service → DB
```
没有任何限流机制，恶意用户可以无限刷 API。

### 迁移后
```
请求 → SentinelRateLimitInterceptor(限流) → SaInterceptor(鉴权) → CsrfInterceptor → Controller
          ↓ (超 QPS)
       429 Too Many Requests
```

### 变更清单

| 文件 | 变更 |
|---|---|
| `pom.xml` | 添加 `sentinel-core:1.8.10` |
| `SentinelConfig.java` | 新建：`@PostConstruct` 注册 4 条 FlowRule + `@Bean` 拦截器 |
| `SentinelRateLimitInterceptor.java` | 新建：`HandlerInterceptor` + `SphU.entry()`/`exit()` |
| `SaTokenConfigure.java` | 注册 Sentinel 拦截器 order=-1 + `ObjectProvider` 可选注入 |
| `application.properties` | `app.sentinel.*` 配置 |
| `application-test.properties` | `app.sentinel.enabled=false` |
| `RateLimitIntegrationTest.java` | 新建：限流基础设施验证测试 |

## 2. 关键难点解析

### 难点 1：SphU.entry() / entry.exit() 必须成对

Sentinel 内部维护每个资源的通过/拒绝计数。`entry()` 创建计数上下文，`exit()` 释放。
如果 `exit()` 没被调用，计数永远不释放，后续请求会被误判为限流。

```java
Entry entry = null;
try {
    entry = SphU.entry(resource);  // 创建计数上下文
    return true;
} catch (BlockException ex) {
    // entry 为 null（没创建成功），不需要 exit
    return false;
} finally {
    if (entry != null) entry.exit();  // 必须释放
}
```

### 难点 2：自定义 Interceptor vs 官方适配器

Sentinel 提供 `sentinel-spring-webmvc-v6x-adapter`，但：
1. Spring Boot 4.x 基于 Jakarta EE，适配器可能还在 javax 时代
2. 自定义 Interceptor 只有 50 行，路径映射逻辑完全可控
3. 学习项目追求理解原理，不是搬依赖

### 难点 3：ObjectProvider 可选注入

当 `app.sentinel.enabled=false` 时，`SentinelConfig` 不加载，`SentinelRateLimitInterceptor` Bean 不存在。
如果 `SaTokenConfigure` 直接构造器注入，Spring 会报 `NoSuchBeanDefinitionException`。

用 `ObjectProvider<T>` + `getIfAvailable()` 实现优雅降级：

```java
public SaTokenConfigure(..., ObjectProvider<SentinelRateLimitInterceptor> interceptor) {
    this.rateLimitInterceptor = interceptor.getIfAvailable(); // null if not available
}

// 注册时 null check
if (rateLimitInterceptor != null) {
    registry.addInterceptor(rateLimitInterceptor).order(-1);
}
```

## 3. 简历 STAR 写法

**Situation**：社区 API 没有限流保护，恶意用户可以无限刷接口，导致 DB 压力飙升。

**Task**：引入限流机制，对核心 API 设置 QPS 阈值，超过阈值返回 429。

**Action**：
- 引入 Alibaba Sentinel SDK，通过自定义 `HandlerInterceptor` + `SphU.entry()` 实现 QPS 限流
- 拦截器注册在鉴权之前（order=-1），被限流的请求不浪费资源做 Token 校验
- 4 条规则：帖子列表 100 QPS、帖子详情 50 QPS、验证码 10 QPS、兜底 200 QPS
- 通过 `ObjectProvider` 实现可选注入，Sentinel 关闭时零影响
- SphU.entry()/exit() 用 try-finally 保证成对调用，避免计数泄漏

**Result**：核心 API 受到 QPS 保护，验证码接口 10 QPS 有效防刷。新增 2 个限流测试，全量 42 个测试全绿。

## 4. 原理详解

### Sentinel 滑动窗口算法

Sentinel 使用滑动窗口（而非固定窗口）统计 QPS：
- 将 1 秒分成多个小窗口（默认 500ms × 2）
- 每秒向前滑动，淘汰最老的窗口
- 避免了固定窗口在边界时刻的流量突增问题

```
固定窗口：     |----1秒----|----1秒----|
             ↑ 边界时刻可能 2 倍流量

滑动窗口：  |--w1--|--w2--|
               ↑ 随时滑动，更精确
```

### 拦截器链顺序

```
order -1: Sentinel 限流（最先执行，保护后续所有组件）
order  0: SaToken 鉴权（检查登录、角色、权限）
order  1: CSRF 防护（双重提交 Cookie）
```

为什么限流在最前面？因为被限流的请求应该尽快返回 429，不浪费 CPU 在鉴权和 CSRF 校验上。

## 5. 面试八股文整理

**Q: Sentinel 和 Guava RateLimiter 有什么区别？**

A: Guava RateLimiter 是单机限流，基于令牌桶算法，只能保护单个 JVM 内的资源。
Sentinel 除了单机限流，还支持集群限流、熔断降级、系统保护等，功能更全面。
当前项目用 Sentinel-core（纯 Java SDK），和 Guava RateLimiter 的定位类似。

**Q: 为什么用自定义 Interceptor 而不是 Sentinel 官方适配器？**

A: Spring Boot 4.x 基于 Jakarta EE（jakarta.servlet.*），官方适配器可能还在 javax 时代。
自定义 Interceptor 只有 50 行，路径映射逻辑透明可控，也避免了版本兼容风险。

**Q: SphU.entry() 和 entry.exit() 为什么必须成对出现？**

A: Sentinel 内部为每个资源维护一个滑动窗口计数器。entry() 创建计数上下文，exit() 释放。
如果 exit() 没调用，计数永远不释放，后续所有请求都会被误判为限流（计数泄漏）。

## 6. 技术选型对比

| 方案 | 集群支持 | 熔断 | 复杂度 | 本项目选择 |
|---|---|---|---|---|
| Sentinel | ✅ | ✅ | 中 | ✅ |
| Guava RateLimiter | ❌ | ❌ | 低 | 不选（功能单一） |
| Bucket4j | ❌ | ❌ | 低 | 不选（社区小） |
| Nginx limit_req | ✅ | ❌ | 低 | 不选（不在应用层） |

## 7. 面试叙事模板

### 30 秒电梯版
> 我为社区 API 引入了 Sentinel 限流保护，通过自定义 HandlerInterceptor 在鉴权之前拦截请求，
> 对帖子列表、详情、验证码等核心接口设置了 QPS 阈值，超限返回 429。
> SphU.entry()/exit() 用 try-finally 保证成对调用，避免计数泄漏。

### 2 分钟详细版
> 社区 API 之前没有限流保护，恶意用户可以无限刷接口。
> 我引入了 Alibaba Sentinel 的 core SDK，不依赖 Dashboard，纯 Java 实现。
>
> 集成方式上，我没有用官方的 webmvc 适配器，因为 Spring Boot 4.x 基于 Jakarta EE，
> 适配器可能还在 javax 时代。自己写了一个 HandlerInterceptor，只有 50 行，
> 路径映射逻辑完全可控。
>
> 拦截器注册在鉴权之前（order=-1），被限流的请求不浪费资源做 Token 校验。
> 4 条规则覆盖了帖子列表（100 QPS）、帖子详情（50 QPS）、验证码（10 QPS，防刷）和兜底（200 QPS）。
>
> 有一个关键的工程细节：SphU.entry() 和 entry.exit() 必须成对调用，
> 用 try-finally 保证，否则 Sentinel 内部计数不释放，后续请求全部被误判。
> 另外通过 ObjectProvider 实现可选注入，Sentinel 关闭时应用零影响。
