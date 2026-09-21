# Ticket: DistributedLock 工具类实现

**依赖**：无
**阻塞**：ticket-02

## 范围

1. 新建 `service/DistributedLock.java`：注入 `StringRedisTemplate`
2. `tryLock(key, ttlSeconds)`：使用 `SET key value NX EX` 原子操作，value 为 UUID 防误删
3. `unlock(key, value)`：Lua 脚本原子释放（仅释放自己的锁）
4. TTL 默认 3 秒防死锁

## 验收

- `mvn compile` 成功
- 锁获取/释放逻辑正确
