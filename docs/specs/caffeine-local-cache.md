# Spec: Caffeine 本地缓存加速热读路径

## 概述

为帖子详情查询和用户公开资料查询添加 Caffeine 本地缓存，减少数据库压力，提升读多写少场景下的响应速度。

## 缓存目标

| 方法 | 缓存名 | Key | TTL | 理由 |
|---|---|---|---|---|
| `CommunityPostService.findPublishedById(id)` | `postDetail` | postId | 60s | 帖子详情是最高频读路径，聚合 3 次 DB 查询 |
| `ProfileService.findPublic(userId)` | `publicProfile` | userId | 60s | 用户资料页高频访问，含聚合计数 |

## 缓存失效触发点

| 写操作 | 驱逐缓存 | 原因 |
|---|---|---|
| `PostPublishingService.update()` | `postDetail` | 帖子内容变更 |
| `PostPublishingService.delete()` | `postDetail` | 帖子被删除 |
| `PostInteractionService.like()` | `postDetail` | likeCount 变化 |
| `PostInteractionService.unlike()` | `postDetail` | likeCount 变化 |
| `PostInteractionService.favorite()` | `postDetail` | favoriteCount 变化 |
| `PostInteractionService.unfavorite()` | `postDetail` | favoriteCount 变化 |
| `CommunityCommentService.create()` | `postDetail` | commentCount 可能变化 |
| `CommunityCommentService.delete()` | `postDetail` | commentCount 可能变化 |

## 技术方案

### 依赖
- `spring-boot-starter-cache`（Spring Boot BOM 管理版本）
- `com.github.ben-manes.caffeine:caffeine`（Spring Boot BOM 管理版本）

### 配置类
- 新建 `config/CacheConfig.java`
  - `@EnableCaching` 开启缓存
  - `CaffeineCacheManager` Bean，配置 `expireAfterWrite(60s)` + `maximumSize(1000)`

### 属性配置
- `application.properties`：`spring.cache.type=caffeine`
- `application-test.properties`：`spring.cache.type=none`（保护现有 38 个测试不受缓存影响）

### 注解应用
- `@Cacheable(cacheNames="postDetail", key="#id")` — 帖子详情
- `@Cacheable(cacheNames="publicProfile", key="#userId")` — 用户资料
- `@CacheEvict(cacheNames="postDetail", key="#postId")` — 帖子写操作
- `@CacheEvict(cacheNames="postDetail", key="#id")` — PostPublishingService 中的参数名适配

## 测试策略

1. **test profile 禁用缓存**：`spring.cache.type=none`，确保现有 38 个测试零改动
2. **缓存行为测试**（新建 `CacheIntegrationTest.java`）：
   - 使用 `@ActiveProfiles("caffeine-test")` 启用缓存
   - 测试 1：连续两次调用 `findPublishedById`，验证第二次命中缓存
   - 测试 2：调用 `update()` 后再调用 `findPublishedById`，验证返回新数据
3. 最终 `mvn test` 所有测试全绿

## 不做的事

1. **不缓存列表查询**：分页参数组合多，命中率低，缓存收益小
2. **不用 Redis 缓存**：当前单体部署，Caffeine 本地缓存延迟更低（纳秒级 vs 毫秒级）
3. **不做多级缓存**：学习阶段不需要 L1+L2 复杂架构
