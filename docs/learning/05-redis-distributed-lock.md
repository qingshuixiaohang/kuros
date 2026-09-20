# 切片 #5：Redis 分布式锁防止并发重复操作

## 1. 架构迁移全景

### 迁移前
```
并发请求 A ─┐                    ┌─ existsById() → false ──→ save() ──→ ✅
             ├→ PostInteractionService.like()
并发请求 B ─┘                    └─ existsById() → false ──→ save() ──→ ❌ 主键冲突
```

两个并发请求同时通过 `existsById()` 检查，都尝试保存，导致主键冲突或重复计数。

### 迁移后
```
并发请求 A ──→ tryLock() → 获取成功 ──→ 业务逻辑 ──→ unlock()
并发请求 B ──→ tryLock() → 获取失败 ──→ 返回当前状态（幂等）
```

### 变更清单

| 文件 | 变更 |
|---|---|
| `DistributedLock.java` | 新建：`tryLock()` / `unlock()` + Lua 脚本释放 |
| `PostInteractionService.java` | `like()` / `favorite()` 加分布式锁 |
| `UserFollowService.java` | `follow()` 加分布式锁 |

## 2. 关键难点解析

### 难点 1：锁值用 UUID 防止误删

经典错误：A 获取锁 → 处理超时 → 锁自动过期 → B 获取锁 → A 释放锁（误删 B 的锁）。

```java
// ❌ 错误：无条件删除
redisTemplate.delete(key);  // 可能删除别人的锁

// ✅ 正确：Lua 脚本验证值后删除
String script = "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                "return redis.call('del', KEYS[1]) else return 0 end";
```

### 难点 2：Lua 脚本保证原子性

`GET + compare + DEL` 三步操作不是原子的，比较和删除之间可能被其他客户端修改。
Redis 执行 Lua 脚本是原子的（单线程），保证判断和删除不会被打断。

### 难点 3：快速失败 vs 重试

本切片选择**快速失败**（获取不到锁直接返回当前状态），因为：
1. 点赞/收藏/关注是幂等操作——返回当前状态等于"已经操作过"
2. 重试会增加 Redis 压力和延迟
3. 并发冲突是小概率事件，快速失败对用户体验影响极小

## 3. 简历 STAR 写法

**Situation**：社区点赞/收藏/关注接口没有并发控制，快速双击会导致重复操作或主键冲突。

**Task**：引入分布式锁，保证同一用户对同一资源的操作不会并发执行。

**Action**：
- 基于 Redis `SET NX EX` 实现分布式锁，UUID 值 + Lua 脚本释放
- 3 秒 TTL 防止死锁，获取失败快速返回当前状态（幂等性）
- 锁粒度为资源级（`lock:like:{postId}:{userId}`），不影响不同用户/帖子的并发

**Result**：彻底消除并发重复操作问题，现有 42 个测试全绿。

## 4. 原理详解

### SET NX EX 原子操作

```
SET lock:like:post1:user1 uuid-value NX EX 3
```

- `NX`：key 不存在时才设置（互斥）
- `EX 3`：3 秒后过期（防死锁）
- 这两个参数在同一条 Redis 命令中，是原子操作

### 与 synchronized 的区别

| 特性 | synchronized | Redis SET NX |
|---|---|---|
| 作用范围 | 单 JVM | 跨 JVM |
| 释放方式 | 自动（方法退出） | 手动（DEL / 过期） |
| 死锁风险 | 低（JVM 处理） | 需要 TTL 兜底 |
| 适用场景 | 单体 | 微服务 |

## 5. 面试八股文整理

**Q: 为什么释放锁要用 Lua 脚本？**

A: GET + compare + DEL 不是原子操作。在 GET 和 DEL 之间，锁可能已经过期被其他客户端获取。
Lua 脚本在 Redis 中是原子执行的，保证判断值相等和删除 key 不会被打断。

**Q: 为什么不用 RedLock？**

A: RedLock 解决的是多个 Redis 实例的高可用锁问题（某个实例宕机后锁不丢失）。
当前项目是单 Redis 实例，SET NX EX 已经足够。引入 RedLock 是过度设计。
未来拆微服务如果需要多 Redis 实例再考虑。

**Q: 快速失败和重试策略怎么选？**

A: 幂等操作（点赞、关注）适合快速失败——返回当前状态等于"已经做过"。
非幂等操作（扣库存、转账）需要重试或排队。
重试时要用指数退避（exponential backoff），避免雪崩。

## 6. 技术选型对比

| 方案 | 互斥 | 防死锁 | 复杂度 | 本项目选择 |
|---|---|---|---|---|
| Redis SET NX EX | ✅ | ✅ (TTL) | 低 | ✅ |
| Redisson | ✅ | ✅ (Watchdog) | 中 | 不选（过重） |
| ZooKeeper | ✅ | ✅ (临时节点) | 高 | 不选（需新基础设施） |
| 数据库悲观锁 | ✅ | ✅ | 低 | 不选（DB 压力大） |

## 7. 面试叙事模板

### 30 秒电梯版
> 我用 Redis SET NX EX 实现了分布式锁，防止并发重复点赞/收藏/关注。
> 锁值用 UUID 防止误删，释放用 Lua 脚本保证原子性，3 秒 TTL 防死锁。
> 获取失败快速返回当前状态，保证幂等性。

### 2 分钟详细版
> 社区点赞、收藏、关注接口在并发场景下可能出现重复操作。
> 比如用户快速双击点赞按钮，两个请求几乎同时到达，
> 都通过了 existsById() 检查，都执行了 save()，导致主键冲突或计数多加。
>
> 我引入了 Redis 分布式锁来防止并发。锁的 key 是资源级的，
> 比如 `lock:like:{postId}:{userId}`，这样不同用户对不同帖子的操作互不影响。
>
> 锁的实现是 SET NX EX 原子操作——NX 保证互斥，EX 3 秒防止死锁。
> 锁值用 UUID，释放时用 Lua 脚本先验证值再删除，防止误删别人的锁。
>
> 获取失败策略是快速失败——直接返回当前状态。因为点赞/关注是幂等操作，
> "已经点过了"和"刚刚点上"对用户体验没有区别。
> 如果未来要支持非幂等操作（比如扣库存），就需要改成重试或排队。
