# ADR 0005: Feed 流——Redis ZSet Timeline + Push-on-publish

**状态**: 已接受（2026-09-21）  
**上下文**: 切片 #12「Feed 流（ZSet Timeline + Push/Pull 混合）」  
**关联**: [spec](../specs/feed-timeline.md) · [前序 ADR 0004](0004-async-interaction-rocketmq.md)

## 决策

关注流采用 **Redis ZSet Timeline + Push-on-publish** 模型：

1. 每个用户一个 ZSet（key `feed:timeline:{userId}`），member=postId，
   score=publishedAt epoch millis。
2. 帖子发布后（事务提交后），`@TransactionalEventListener(phase = AFTER_COMMIT)`
   异步触发扇出：经 Feign 取作者粉丝列表，对每个粉丝 `ZADD` 到其 timeline ZSet。
3. 读路径从 ZSet `ZREVRANGE` 按时间倒序取 postId 分页，再 `findAllById` 查帖子详情。
4. 容量上限 500 条（`ZREMRANGEBYRANK` 裁剪）+ 30 天 TTL 惰性过期。
5. 推荐流和最新流**复用现有 DB 查询路径**（不改排序逻辑，留 #13 增强）。

## 理由

| 维度 | 选 Push-on-publish ZSet | 拒绝 Pull-on-read | 拒绝 RocketMQ 异步扇出 |
|---|---|---|---|
| 读延迟 | 一次 ZREVRANGE + 批量 DB 查 | 先查关注列表再逐作者查帖子（N+1） | 同 ZSet 但多 MQ 链路 |
| 写放大 | 发帖时 N 次 ZADD（N=粉丝数） | 零（读时算） | N 条 MQ 消息 |
| 一致性 | 发帖事务提交后立即可见于粉丝 | 可能延迟/不一致 | 最终一致（MQ 延迟） |
| 复杂度 | 中（Feign + ZSet） | 低（纯 DB 查） | 高（MQ + ZSet + 补偿） |
| 适用边界 | 粉丝数 <5000、发帖频率中等（**本项目**） | 粉丝极多但读少（反本项目场景） | 大 V 场景/超大规模 |

本项目是求职作品，数据规模小，Push-on-publish 是最简单且最易讲清的架构选择。

## 不在范围

- 大 V 推拉混合（粉丝 >5000 时不推，读时拉）
- 热度衰减算法（#13）
- 取消关注时清理 timeline（#13）
- 游标分页（#13）

## 后果

- 正面：读路径 O(1)（ZSet range + 批量 DB），面试可讲微博/Twitter 同款架构；
  发帖写放大可控（粉丝上限 5000）。
- 负面：新关注的人的历史帖子不在 timeline（需主动"拉一次"回填，本切片不实现）；
  取消关注后 timeline 残留帖子（读路径过滤兜底，#13 清理）。
