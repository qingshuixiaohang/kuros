## 概述

切片 #12「Feed 流（ZSet Timeline + Push/Pull 混合）」的代码落地。Closes #71。

## 改动摘要

### 架构核心（ADR 0005）

关注流采用 Redis ZSet Timeline + Push-on-publish 模型：
- 发帖时（事务提交后）把 postId 推到所有粉丝的 timeline ZSet（`feed:timeline:{userId}`）
- 读时 `ZREVRANGE` 按时间倒序取 postId 分页，委托 `CommunityPostService.findPublishedByIds` 构建摘要
- 推荐/最新流**不改**——复用现有 `GET /api/v1/posts?sort=hot|latest`

### 文件清单

**后端（新增）**
- `feed/redis/FeedTimelineStore.java`：Pipeline 批量 ZADD + ZREMRANGEBYRANK(500) 裁剪 + EXPIRE(30d)，一次 RTT 完成所有粉丝的写入
- `feed/event/PostPublishedEvent.java`：Spring Application Event record（postId + authorId + publishedAt）
- `feed/event/FeedFanoutListener.java`：`@TransactionalEventListener(AFTER_COMMIT)` 扇出，Feign 取粉丝列表 → pushToTimelines，异常 log.warn 跳过不影响发帖
- `feed/service/FeedService.java`：读路径 ZSet → postId 列表 → 委托 CommunityPostService 构建摘要（零分叉）
- `feed/web/FeedController.java`：`GET /api/v1/feed/following`（需登录）

**后端（改造）**
- `PostPublishingService.publish()`：emit `PostPublishedEvent`（注入 `ApplicationEventPublisher`），发帖成功后触发扇出

**前端（改造）**
- `lib/api.ts`：新增 `fetchFollowingFeed({page, pageSize})` → `GET /api/v1/feed/following`
- `community-home.tsx`：关注 tab 改调 `fetchFollowingFeed`（不再客户端 filter mock 数据），降级空状态

**测试（新增）**
- `FeedTimelineIntegrationTest.java`：Testcontainers Redis + H2，7 个测试覆盖 push/read/分页/容量裁剪/空 timeline/findFollowingFeed 端到端

## 编译级验证（AI 完成）

- ✅ backend compile：BUILD SUCCESS（零 warning）
- ✅ backend test-compile：BUILD SUCCESS
- ✅ frontend tsc --noEmit：零 error

## 耗时测试（交用户 / CI）

```bash
# 全量测试（含新增 FeedTimelineIntegrationTest）
./mvnw test

# compose 冒烟（验证发帖 → 粉丝 timeline 端到端）
docker compose up --build -d
```

## 不在本切片范围

- 热度衰减算法（推荐流 ranking 增强，#13）
- 取消关注时清理 timeline（#13）
- 游标分页 / 已读标记 / 未读 badge（#13）
- 大 V 推拉混合（粉丝 >5000）
- 推荐流个性化算法（最后单独立项）
