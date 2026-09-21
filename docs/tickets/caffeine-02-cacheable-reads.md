# Ticket: 读方法加 @Cacheable 注解

**依赖**：ticket-01
**阻塞**：ticket-04

## 范围

1. `CommunityPostService.findPublishedById(String id)` 加 `@Cacheable(cacheNames="postDetail", key="#id")`
2. `ProfileService.findPublic(String userId)` 加 `@Cacheable(cacheNames="publicProfile", key="#userId")`

## 验收

- 编译通过
- 缓存行为测试（ticket-04）验证命中
