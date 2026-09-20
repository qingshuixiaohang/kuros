# 切片 #7：Actuator + Micrometer 应用监控

## 1. 架构迁移全景

### 迁移前
Actuator 只暴露 `/actuator/health` 且 `show-details=never`，没有自定义业务指标。

### 迁移后
- 暴露 health、info、metrics、prometheus 四个端点
- 自定义帖子发布计数器（Counter）和发布耗时计时器（Timer）
- 自定义 StorageHealthIndicator 检查存储策略连通性

### 变更清单

| 文件 | 变更 |
|---|---|
| `MetricsConfig.java` | 新建：Counter + Timer Bean |
| `StorageHealthIndicator.java` | 新建：检查存储策略连通性 |
| `PostPublishingService.java` | 注入 Counter/Timer，publish() 中埋点 |
| `application.properties` | 扩展暴露端点 + 应用信息 |

## 2. 关键难点解析

### Spring Boot 4.x Health 包迁移

Spring Boot 4.x 将 Health 相关类从 `org.springframework.boot.actuate.health` 迁移到了
`org.springframework.boot.health.contributor`。这是一个重大破坏性变更。

```java
// Spring Boot 3.x
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

// Spring Boot 4.x
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
```

### Counter vs Timer 使用场景

- **Counter（计数器）**：只增不减，用于统计总量（如总发帖数）
- **Timer（计时器）**：记录每次操作的耗时和次数，自动计算 p50/p95/p99

## 3. 简历 STAR 写法

**Action**：增强 Actuator 配置，添加自定义业务指标（帖子发布计数器和耗时计时器），
实现 StorageHealthIndicator 检查存储连通性。健康检查结果接入 K8s 探针。

**Result**：运维可以通过 Prometheus + Grafana 监控发帖速率和性能趋势，
存储故障时健康检查自动返回 DOWN，K8s 自动重启 Pod。

## 4. 面试八股文整理

**Q: Counter 和 Gauge 的区别？**

A: Counter 只增不减（如总发帖量），Gauge 可增可减（如当前在线用户数）。
Counter 用 `increment()`，Gauge 用 `set()` 或注册一个 lambda。

**Q: Spring Boot 4.x 的 Health 包为什么迁移？**

A: Spring Boot 4 对 actuator 做了模块化重构，health 子系统独立为 `spring-boot-health` 模块，
不再强依赖 actuator。这样只需要健康检查的项目可以不引入完整 actuator。

## 5. 技术选型对比

| 指标类型 | 用途 | 本项目使用 |
|---|---|---|
| Counter | 总量统计 | posts.published.total |
| Timer | 耗时统计 | posts.publish.duration |
| Gauge | 当前状态 | 未使用（暂无需要监控的动态值） |

## 6. 面试叙事模板

> 我增强了 Spring Boot Actuator 配置，添加了自定义业务指标。
> 帖子发布用 Counter 统计总量、Timer 统计耗时，数据可以通过 Prometheus 采集。
> 还实现了 StorageHealthIndicator 检查存储策略连通性，
> 存储故障时健康检查返回 DOWN，K8s 会自动重启 Pod。
> 这里有个坑：Spring Boot 4.x 把 Health 类从 actuate.health 迁移到了 health.contributor 包，
> 不看源码根本不知道这个破坏性变更。
