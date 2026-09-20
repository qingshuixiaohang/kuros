# Ticket: 写方法加 @CacheEvict 注解

**依赖**：ticket-01
**阻塞**：ticket-04

## 范围

1. `PostPublishingService.update()` 加 `@CacheEvict(cacheNames="postDetail", key="#postId")`
2. `PostPublishingService.delete()` 加 `@CacheEvict(cacheNames="postDetail", key="#postId")`
3. `PostInteractionService.like/unlike/favorite/unfavorite()` 加 `@CacheEvict(cacheNames="postDetail", key="#postId")`
4. `CommunityCommentService.create()` 加 `@CacheEvict(cacheNames="postDetail", key="#postId")`
5. `CommunityCommentService.delete()` 加 `@CacheEvict(cacheNames="postDetail", key="#postId")`

## 验收

- 编译通过
- 缓存行为测试（ticket-04）验证失效
