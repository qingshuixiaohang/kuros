# Spec: Redis 分布式锁防止并发重复操作

## 概述

使用 Redis `SET NX EX` 实现分布式锁，防止并发请求导致重复点赞/收藏/关注。

## 锁目标

| 操作 | 锁 Key | 说明 |
|---|---|---|
| `PostInteractionService.like()` | `lock:like:{postId}:{userId}` | 防重复点赞 |
| `PostInteractionService.favorite()` | `lock:favorite:{postId}:{userId}` | 防重复收藏 |
| `UserFollowService.follow()` | `lock:follow:{targetId}:{followerId}` | 防重复关注 |

## 技术方案

- **锁实现**：`SET key value NX EX`（原子操作，不存在则设置 + 过期时间）
- **锁值**：UUID（只有持有者能释放锁，防止误删他人锁）
- **TTL**：3 秒（防止死锁——持有者崩溃后锁自动释放）
- **获取失败策略**：快速失败（不重试，直接抛异常或返回当前状态）
- **释放方式**：Lua 脚本（原子性判断 + 删除，GET + compare + DEL 三步原子化）

## 不做的事

1. 不用 RedLock（单 Redis 实例足够，RedLock 是多实例方案）
2. 不做锁续期（3 秒 TTL 足够覆盖业务操作，续期增加复杂度）
