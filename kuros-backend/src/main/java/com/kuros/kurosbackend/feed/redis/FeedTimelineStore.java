package com.kuros.kurosbackend.feed.redis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

/**
 * Feed Timeline 的 Redis ZSet 存储。
 *
 * 数据模型：每个用户一个 ZSet（key = feed:timeline:{userId}）
 * - member = postId（帖子 ID）
 * - score = publishedAt 的 epoch millis（时间越早分数越低，ZREVRANGE 自然按时间倒序）
 *
 * 容量控制：每次 push 后裁剪超过 maxSize 的旧条目（ZREMRANGEBYRANK）。
 * TTL：每次 push 刷新 30 天 TTL（用户活跃则 timeline 保持新鲜；不活跃则惰性过期回收内存）。
 *
 * 面试叙事：这是微博/Twitter 同款 "Timeline as a ZSet" 架构——发帖时把 postId 推给粉丝，
 * 粉丝读时只需 ZREVRANGE 取自己的 timeline，O(log(N)+M) 复杂度，N 是 timeline 容量、M 是取回条数。
 */
@Component
public class FeedTimelineStore {

    private static final String KEY_PREFIX = "feed:timeline:";

    private final StringRedisTemplate redisTemplate;
    private final int maxSize;
    private final long ttlSeconds;

    public FeedTimelineStore(
            StringRedisTemplate redisTemplate,
            @Value("${app.feed.timeline.max-size:500}") int maxSize,
            @Value("${app.feed.timeline.ttl-seconds:2592000}") long ttlSeconds
    ) {
        this.redisTemplate = redisTemplate;
        this.maxSize = maxSize;
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * 把一个帖子推到多个粉丝的 timeline（发帖时扇出调用）。
     *
     * 对每个 followerId：ZADD + 容量裁剪 + 刷新 TTL。
     * 使用 Pipeline 批量发送，减少 RTT（粉丝数 > 100 时收益显著）。
     *
     * @param postId      帖子 ID
     * @param publishedAt 发布时间（作为 ZSet score）
     * @param followerIds 粉丝用户 ID 列表
     */
    public void pushToTimelines(String postId, LocalDateTime publishedAt, List<String> followerIds) {
        if (followerIds == null || followerIds.isEmpty()) return;
        double score = toEpochMillis(publishedAt);
        // Pipeline 批量：一次 RTT 完成所有粉丝的 ZADD + 裁剪 + TTL 刷新
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            byte[] postIdBytes = postId.getBytes();
            for (String followerId : followerIds) {
                byte[] keyBytes = timelineKey(followerId).getBytes();
                // ZADD（如果 postId 已存在则更新 score——同一帖子不会重复推，但防御性处理）
                connection.zSetCommands().zAdd(keyBytes, score, postIdBytes);
                // 裁剪：保留 score 最高的 maxSize 条（删掉最旧的）
                connection.zSetCommands().zRemRange(keyBytes, 0, -(maxSize + 1));
                // 刷新 TTL
                connection.keyCommands().expire(keyBytes, ttlSeconds);
            }
            return null; // Pipeline callback 要求返回 null
        });
    }

    /**
     * 读 timeline：按时间倒序取 offset..offset+limit-1 的 postId 列表。
     *
     * ZREVRANGE 语义：index 0 = score 最高（最新发布）；index -1 = score 最低（最旧）。
     * 所以 ZREVRANGE(offset, offset+limit-1) 就是"跳过 offset 个最新的，取接下来 limit 个"。
     */
    public List<String> readTimeline(String userId, int offset, int limit) {
        String key = timelineKey(userId);
        Set<String> ids = redisTemplate.opsForZSet().reverseRange(key, offset, (long) offset + limit - 1);
        return ids == null ? List.of() : List.copyOf(ids);
    }

    /**
     * timeline 当前容量（用于测试断言 + 监控）。
     */
    public long size(String userId) {
        Long count = redisTemplate.opsForZSet().size(timelineKey(userId));
        return count == null ? 0 : count;
    }

    /**
     * 清空某用户的 timeline（测试用 @BeforeEach + 取消关注清理）。
     */
    public void clear(String userId) {
        redisTemplate.delete(timelineKey(userId));
    }

    /**
     * 检查某帖子是否在某用户的 timeline 里（测试断言用）。
     */
    public boolean contains(String userId, String postId) {
        Double score = redisTemplate.opsForZSet().score(timelineKey(userId), postId);
        return score != null;
    }

    private String timelineKey(String userId) {
        return KEY_PREFIX + userId;
    }

    private static double toEpochMillis(LocalDateTime dateTime) {
        return dateTime.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}
