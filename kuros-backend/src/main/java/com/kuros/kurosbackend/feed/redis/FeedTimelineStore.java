package com.kuros.kurosbackend.feed.redis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
     * 游标翻页读 timeline（切片 #13 / rp-04）：返回严格排在游标 (cursorScore, cursorPostId) 之后、
     * 按时间倒序（score DESC，同分 postId DESC）的最多 limit 个 postId。
     *
     * 为什么用游标而不是 offset？offset 翻页（{@link #readTimeline}）要 ZREVRANGE 跳过前 offset 个成员，
     * 深度越大跳得越多；游标用 ZREVRANGEBYSCORE 直接定位到「分数低于游标」的位置起取 limit 个，翻到多深都是 O(log N + limit)。
     *
     * 同分（同一发布毫秒）怎么去重不丢不重？
     * Redis 对同分成员按「字典序倒序」排列，故全序是 (score DESC, postId DESC)。以游标项为界：
     * 同分且 postId &gt;= cursorPostId 的成员属于「上一页已返回」（含游标项本身），跳过；
     * 同分且 postId &lt; cursorPostId 的才是下一页。为覆盖这段同分边界，多取一个 limit 作缓冲再过滤。
     * 前提假设：同一毫秒的帖子数远小于 limit（score 是发布 epoch millis，单用户关注流内同毫秒暴量不现实）。
     *
     * postId 用 String.compareTo 比较与 Redis 的二进制字典序一致：UUID 均为 ASCII，UTF-16 码元序 == 字节序。
     *
     * @param cursorScore 游标分数（上一页最后一条的 score）；null 表示第一页（从最新起）
     * @param cursorPostId 游标 postId（同分兜底）；null 表示第一页
     * @param limit       本页最多返回条数
     */
    public List<String> readTimelineByCursor(String userId, Long cursorScore, String cursorPostId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        String key = timelineKey(userId);
        boolean firstPage = cursorScore == null || cursorPostId == null;
        // max 用闭区间（含游标分数）以便捕获同分边界的下一页成员，随后按 postId 过滤掉已返回的
        double max = firstPage ? Double.POSITIVE_INFINITY : cursorScore.doubleValue();
        // 多取一个 limit 作同分边界缓冲：最坏情况上一页整页都与游标同分，需跳过至多 limit 个已返回成员
        long fetchCount = firstPage ? limit : (long) limit + limit;
        Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, Double.NEGATIVE_INFINITY, max, 0, fetchCount);
        if (tuples == null || tuples.isEmpty()) {
            return List.of();
        }
        List<String> page = new ArrayList<>(limit);
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            String id = tuple.getValue();
            Double score = tuple.getScore();
            if (id == null || score == null) {
                continue;
            }
            // 跳过游标项本身及同分块中「已返回」（postId >= cursorPostId）的边界成员
            if (!firstPage && score == max && id.compareTo(cursorPostId) >= 0) {
                continue;
            }
            page.add(id);
            if (page.size() == limit) {
                break;
            }
        }
        return page;
    }

    /** 取某帖子在 timeline 中的 score（epoch millis）；不存在返回 0。供构造下一页游标使用。 */
    public long scoreOf(String userId, String postId) {
        Double score = redisTemplate.opsForZSet().score(timelineKey(userId), postId);
        return score == null ? 0L : score.longValue();
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
