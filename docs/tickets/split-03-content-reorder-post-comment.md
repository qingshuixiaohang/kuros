# Ticket: 重排 post + comment 模块（Phase A-3）

**父 Issue**：#67（切片 #10：服务拆分——用户域独立微服务）
**依赖**：split-02
**阻塞**：split-04

## 范围

1. `post` 模块包：`CommunityPost`/`ContentTag`/`PostMedia`/`PostStatus`/`PostType` 等实体、`CommunityPostService`/`PostPublishingService`、`CommunityPostController`、帖子相关 DTO 与仓储
2. `comment` 模块包：`CommunityComment`/`CommentStatus`、`CommunityCommentService`、`CommunityCommentController`、评论 DTO 与仓储
3. 更新交叉引用（interaction/report 对 post 的引用等），纯移动零行为变化
4. 验收：全量后端测试绿

## 验收

- [ ] `post`/`comment` 模块包建立
- [ ] 全量测试绿，无行为变化
