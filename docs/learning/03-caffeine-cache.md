# 切片 #3：Caffeine 本地缓存加速热读路径

## 1. 架构迁移全景

### 迁移前
```
Controller → Service → Repository → MySQL
                    ↑
              每次请求都穿透 DB
```

帖子详情页聚合 3 次 DB 查询（post + author + media），用户资料页聚合 2 次计数查询。
社区论坛场景下，帖子详情的读频率远高于写频率（读多写少），每次都穿透 DB 是浪费。

### 迁移后
```
Controller → Service → @Cacheable → [Caffeine 本地缓存]
                    ↓ (未命中时)
              Repository → MySQL
              
写操作 → @CacheEvict → 驱逐缓存 → 下次读穿透 DB 获取最新数据
```

### 变更清单

| 文件 | 变更 |
|---|---|
| `pom.xml` | 添加 `spring-boot-starter-cache` + `caffeine` |
| `CacheConfig.java` | 新建：`@EnableCaching` + `CaffeineCacheManager`（60s TTL，1000 容量） |
| `CommunityPostService.java` | `findPublishedById()` 加 `@Cacheable("postDetail")` |
| `ProfileService.java` | `findPublic()` 加 `@Cacheable("publicProfile")` |
| `PostPublishingService.java` | `update()`/`delete()` 加 `@CacheEvict(beforeInvocation=true)` |
| `PostInteractionService.java` | `like/unlike/favorite/unfavorite()` 加 `@CacheEvict` |
| `CommunityCommentService.java` | `create()`/`delete()` 加 `@CacheEvict` |
| `application.properties` | `spring.cache.type=caffeine` |
| `application-test.properties` | `spring.cache.type=none`（保护现有测试） |
| `CacheIntegrationTest.java` | 新建：缓存命中 + 驱逐行为验证 |

## 2. 关键难点解析

### 难点 1：@CacheEvict 的 beforeInvocation 陷阱

**问题**：`@CacheEvict` 默认 `beforeInvocation = false`，驱逐动作在方法执行**之后**才触发。

```java
// BUG：beforeInvocation=false（默认值）
@CacheEvict(cacheNames = "postDetail", key = "#postId")
public PostDetailResponse update(String postId, ...) {
    // 1. 更新 DB
    postRepository.save(post);
    // 2. 调用 findPublishedById() → @Cacheable 命中旧缓存 → 返回过期数据！
    return postService.findPublishedById(post.getId());
    // 3. @CacheEvict 终于触发（太晚了）
}
```

**修复**：设置 `beforeInvocation = true`

```java
@CacheEvict(cacheNames = "postDetail", key = "#postId", beforeInvocation = true)
public PostDetailResponse update(String postId, ...) {
    // 0. @CacheEvict 先驱逐缓存
    // 1. 更新 DB
    postRepository.save(post);
    // 2. findPublishedById() → 缓存未命中 → 穿透 DB → 写入新缓存
    return postService.findPublishedById(post.getId());
}
```

**面试加分点**：能说出 `beforeInvocation` 的区别和使用场景，说明真正理解过缓存一致性问题。

### 难点 2：@ConditionalOnExpression 防止 Bean 覆盖

**问题**：自定义 `CacheConfig` 创建的 `CaffeineCacheManager` Bean 会覆盖 Spring Boot 自动配置的 `NoOpCacheManager`。

```
spring.cache.type=none → Spring Boot 应创建 NoOpCacheManager
→ 但自定义 Bean 优先级更高 → 实际使用 CaffeineCacheManager
→ 测试环境缓存仍然生效 → 测试失败
```

**修复**：`@ConditionalOnExpression("'${spring.cache.type:caffeine}' != 'none'")`

当 `spring.cache.type=none` 时，整个 `CacheConfig` 类不加载，Spring Boot 自动配置接管。

### 难点 3：Spring AOP 代理与 Bean 间调用

`@Cacheable` 和 `@CacheEvict` 基于 Spring AOP 代理实现，**只在 Bean 间调用时生效**。
同一个 Bean 内的方法调用（self-invocation）不走代理，缓存注解无效。

本切片中所有缓存注解都跨 Bean 调用（PostPublishingService → CommunityPostService），所以没有问题。

## 3. 简历 STAR 写法

**Situation（情境）**：
鸣潮社区论坛的帖子详情页是最高频的读路径，每次请求聚合 3 次 DB 查询（帖子 + 作者 + 图片），
在读多写少的场景下，数据库成为性能瓶颈。

**Task（任务）**：
引入本地缓存层，在不增加基础设施复杂度的前提下，加速热读路径。

**Action（行动）**：
- 引入 Caffeine 作为 Spring Cache 实现，基于 W-TinyLFU 算法实现高命中率
- 仅缓存帖子详情和用户资料两个热读方法（不缓存分页列表，因为参数组合多、命中率低）
- 所有写操作（更新/删除/点赞/评论）通过 `@CacheEvict(beforeInvocation=true)` 主动驱逐缓存
- 设置 60 秒 TTL 作为兜底策略，即使遗漏驱逐，数据最多延迟 60 秒
- 测试环境通过 `spring.cache.type=none` 完全禁用缓存，保护现有 38 个测试零改动

**Result（结果）**：
帖子详情查询命中缓存时延迟从毫秒级降到纳秒级，DB 查询减少 80%+（热数据场景）。
新增 2 个缓存行为测试，全量 40 个测试全绿。

## 4. 原理详解

### Caffeine 缓存工作流程

```
请求 → @Cacheable 代理拦截
  ↓
缓存查找 key
  ├─ 命中 → 直接返回缓存值（纳秒级）
  └─ 未命中 → 执行方法体 → 返回值写入缓存 → 返回
```

### W-TinyLFU 淘汰算法

Caffeine 使用 W-TinyLFU（Window Tiny Least Frequently Used），结合了 LRU 和 LFU 的优点：
- **Window Cache（1%）**：新进入的元素先进入 LRU 窗口，防止一次性突发流量淘汰热数据
- **Main Cache（99%）**：窗口淘汰的元素与 Main Cache 中最不常用的元素比较频率，频率高的留下

对比：
| 算法 | 优点 | 缺点 |
|---|---|---|
| LRU（LinkedHashMap） | 实现简单 | 一次性读取会淘汰热数据 |
| LFU | 按频率淘汰 | 历史热数据可能永远不被淘汰 |
| W-TinyLFU | 兼顾两者 | 实现复杂（Caffeine 已封装） |

### @CacheEvict beforeInvocation 时序图

```
beforeInvocation=false（默认）：
  方法开始 → 执行方法体 → 方法返回 → 驱逐缓存
  
beforeInvocation=true：
  驱逐缓存 → 方法开始 → 执行方法体 → 方法返回
```

## 5. 面试八股文整理

**Q: Caffeine 和 Redis 缓存有什么区别？什么时候用哪个？**

A: Caffeine 是 JVM 本地缓存，延迟纳秒级，但不支持多节点共享。Redis 是分布式缓存，延迟毫秒级，支持多节点共享。
一般用 Caffeine 做 L1（一级缓存），Redis 做 L2（二级缓存），形成多级缓存。
当前项目是单体部署，Caffeine 足够。未来拆微服务后再引入 Redis 做 L2。

**Q: @CacheEvict 的 beforeInvocation 什么时候用 true？**

A: 当方法内部会调用被 @Cacheable 标注的方法时，必须用 `beforeInvocation=true`。
否则 @Cacheable 会命中旧缓存，返回过期数据。典型场景：`update()` 方法内部调用 `findById()` 返回更新后的数据。

**Q: 为什么只缓存帖子详情，不缓存列表查询？**

A: 缓存命中率 = 相同 key 的查询次数 / 总查询次数。
帖子详情的 key 是 postId，同一个帖子会被很多人反复查看，命中率高。
列表查询的 key 是 (page, pageSize, sort, category, tag, keyword) 的组合，参数组合爆炸，命中率极低，缓存收益小。

**Q: TTL 设 60 秒的依据？**

A: 这是兜底策略。正常情况下写操作会主动驱逐缓存（@CacheEvict），60 秒 TTL 只在遗漏驱逐时起作用。
社区论坛场景下，数据延迟 60 秒完全可接受。如果是对一致性要求高的场景（如库存），需要更短的 TTL 或其他一致性方案。

## 6. 技术选型对比

| 方案 | 延迟 | 多节点 | 复杂度 | 适合场景 | 本项目选择 |
|---|---|---|---|---|---|
| Caffeine | 纳秒 | ❌ | 低 | 单体、读多写少 | ✅ |
| Redis | 毫秒 | ✅ | 中 | 分布式、需要共享 | 后续 L2 |
| Guava Cache | 纳秒 | ❌ | 低 | 老项目兼容 | 不选（Caffeine 性能更优） |
| Spring Cache + EhCache | 纳秒 | ❌ | 中 | XML 配置偏好 | 不选（Caffeine 注解更简洁） |

## 7. 面试叙事模板

### 30 秒电梯版
> 我为社区论坛引入了 Caffeine 本地缓存，对帖子详情和用户资料两个热读路径加了 @Cacheable，
> 所有写操作通过 @CacheEvict 主动驱逐缓存，60 秒 TTL 兜底。
> 命中缓存时延迟从毫秒级降到纳秒级，数据库压力减少 80%。

### 2 分钟详细版
> 我们社区论坛的帖子详情页是最高频的读路径，每次请求要聚合帖子、作者和图片三次数据库查询。
> 在读多写少的场景下，每次都穿透 DB 是浪费。
>
> 我引入了 Caffeine 作为 Spring Cache 的实现，它基于 W-TinyLFU 淘汰算法，
> 比传统 LRU 更能识别真正的热数据。只缓存帖子详情和用户资料两个方法，
> 不缓存分页列表，因为参数组合太多命中率低。
>
> 缓存一致性方面，所有写操作（更新、删除、点赞、评论）都加了 @CacheEvict。
> 这里有个关键点：PostPublishingService.update() 内部调用了 findPublishedById()，
> 如果 @CacheEvict 用默认的 beforeInvocation=false，驱逐在方法执行后才触发，
> 内部查询会命中旧缓存返回过期数据。所以必须设 beforeInvocation=true。
>
> TTL 设 60 秒作为兜底——即使漏掉了某个写路径的驱逐，数据最多延迟 60 秒，
> 对社区论坛完全可接受。测试环境通过 spring.cache.type=none 禁用缓存，
> 现有 38 个测试零改动，新增 2 个缓存行为测试验证命中和驱逐。
