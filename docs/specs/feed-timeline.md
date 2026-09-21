# Feed 流（ZSet Timeline + Push/Pull 混合）

## Problem Statement

首页三种信息流（推荐/最新/关注）的"关注"tab 目前是纯客户端过滤——前端拿到全量帖子列表后
按 `followed` 数组过滤。两个结构性问题：

1. **不可扩展**：客户端要加载全量帖子才能筛出关注作者的帖子，数据量增长后不可用。
2. **数据不实**：关注 tab 的内容受限于客户端已加载的帖子范围，关注作者的新帖不会被
   发现（除非恰好出现在全量列表中）。

## Solution

后端新增 FeedService 提供三种信息流查询：
- **推荐流**（recommend）：按热度排序（like_count + favorite_count 加权 + 时间衰减），
  复用现有 DB 查询路径。
- **最新流**（latest）：按 published_at DESC，复用现有 DB 查询路径。
- **关注流**（following）：基于 Redis ZSet Timeline + Push-on-publish 模型——用户发帖时
  把 postId 推给所有粉丝的 timeline ZSet，读时从 ZSet 按时间倒序取 postId 列表再查帖子
  详情。

## User Stories

1. 作为登录用户，我想在关注 tab 看到我关注的人发布的帖子，按时间倒序排列，这样我能
   追踪他们的最新动态。
2. 作为登录用户，我想在关注 tab 看到空状态提示（"还没有关注的创作者"），引导我去推荐页。
3. 作为游客，我想在关注 tab 被引导登录（关注流需要登录态）。
4. 作为用户，我想在发布帖子后，我的粉丝能在他们的关注流里看到这篇帖子。
5. 作为用户，我想推荐流按热度排序（综合点赞、收藏、评论数），而不只是最新或随机。
6. 作为用户，我想最新流严格按发布时间倒序。
7. 作为用户，我想取消关注某人后，他的帖子不再出现在我的关注流。
8. 作为用户，我想关注流的分页加载（下拉到底能加载更多）。

## Implementation Decisions

### D1: Redis ZSet Timeline 数据模型

- Key: `feed:timeline:{userId}`
- Member: postId (String)
- Score: publishedAt 的 epoch millis（double）
- 容量上限: `ZREMRANGEBYRANK key 0 -(MAX_SIZE+1)` → 保留最近 500 条（可配置）
- TTL: 30 天（惰性过期，用户不再活跃则 timeline 自动清理）

### D2: Push-on-publish 扇出策略

- 时机: `PostPublishingService.publish()` 完成后（事务提交后）
- 触发: 经 `@TransactionalEventListener(phase = AFTER_COMMIT)` 异步触发
- 扇出: 调 Feign `client.followers(authorId, 1, 5000)` 取粉丝列表（上限 5000，
  超上限作者不在本切片范围），对每个粉丝 `ZADD timeline score postId`
- 降级: Feign 调用失败/粉丝列表为空 → log warn 跳过，不影响发帖成功响应

### D3: 关注流读路径

- 端点: `GET /api/v1/feed/following?page=1&pageSize=20`（需登录）
- 实现:
  1. `ZREVRANGE feed:timeline:{userId} offset offset+pageSize-1` → postId 列表
  2. `postRepository.findAllById(postIds)` → 帖子列表
  3. 过滤已删除/非 PUBLISHED 帖子（发帖后可能被作者删除/管理员下架）
  4. 按 postId 列表原始顺序排列（ZSet 已按时间倒序）
  5. 作者信息批量回填（复用 UserDirectoryFacade.findAuthors）
- 未登录: 401（SaToken 拦截器保护）

### D4: 推荐/最新流复用现有端点

- 不新增端点，仅扩展现有 `GET /api/v1/posts?sort=hot|latest` 的能力
- "推荐" 排序逻辑增强: 现有 `sortOf("hot")` 已按 `like_count DESC, published_at DESC`
  排序，本切片不改排序逻辑（留切片 #13 的"热度衰减算法"增强）
- 前端改动: 关注 tab 改调 `/api/v1/feed/following`，推荐/最新 tab 保持不变

### D5: 取消关注时的 timeline 清理

- 不在本切片范围。取消关注后，timeline ZSet 里仍保留该作者的历史 postId——但读路径
  会按 `findAllById` 的结果过滤（帖子仍在就展示，只是"不是最新鲜的"）。
- 完整清理（取消关注即从 timeline 移除该作者所有 postId）留切片 #13。

### D6: 发帖后自推

- 用户发帖后**不推自己的 timeline**（自己看自己的帖子不是关注流的语义）。
- 自己的帖子出现在"推荐/最新"tab 里。

## Testing Decisions

- **FeedService 单元测试**: 用内嵌 Redis（Testcontainers redis）验证 ZSet 操作
  （push/read/cleanup/上限）
- **FeedFanoutListener 集成测试**: 验证发帖 → Feign 取粉丝 → ZSet 写入的端到端
  （用 Feign 桩，参照 split-09 的 HttpServer 模式）
- **FeedController 集成测试**: 验证关注流端点的鉴权 + 分页 + 过滤已删除帖子
- **主套件兼容**: 新增端点不影响现有测试（独立 URL 路径，无 schema 变更）

## Out of Scope

- 热度衰减算法（推荐流的 ranking 增强，留 #13）
- 取消关注时清理 timeline（留 #13）
- 关注人数超 5000 的大 V 推拉混合策略
- 评论计数纳入热度排序
- 已读标记（timeline 里标记哪些帖子已看过）
- 关注流的"加载更多"（游标分页，留 #13）
- 关注 tab 的未读数 badge
- 推荐流的个性化推荐算法（AI 标签/向量，最后单独立项）

## Further Notes

- 前端已有三 tab UI（community-home.tsx `feed-tabs`），只需改 API 调用。
- 本切片的"推荐流"本质上等于"热帖排序"，真正个性化推荐是最后单独立项的 AI 部分。
- ZSet Timeline 是微博/Twitter 等社交平台 Feed 流的经典架构，面试可深挖推拉混合、
  大 V 策略、active timeline、feed 聚合等话题。
