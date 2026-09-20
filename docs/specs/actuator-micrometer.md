# Spec: Actuator + Micrometer 应用监控

## 概述

增强 Spring Boot Actuator 配置，暴露健康检查、应用信息和 Prometheus 指标端点。
添加自定义业务指标（帖子发布计数器）和自定义健康指示器（检查 Redis/MinIO 连通性）。

## 暴露端点

| 端点 | 说明 | 对外暴露 |
|---|---|---|
| `/actuator/health` | 健康检查（含自定义指示器） | ✅ |
| `/actuator/info` | 应用信息 | ✅ |
| `/actuator/metrics` | 所有指标 | ❌ (内部) |
| `/actuator/prometheus` | Prometheus 格式指标 | ❌ (内部) |

## 自定义指标

- `posts.published.total` (Counter): 帖子发布计数器
- `posts.publish.duration` (Timer): 帖子发布耗时

## 技术方案

- 已有 `spring-boot-starter-actuator` 依赖
- 新建 `MetricsConfig.java`: 自定义 Counter + Timer Bean
- 新建 `StorageHealthIndicator.java`: 检查存储策略连通性
- `application.properties` 配置暴露端点
