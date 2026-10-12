package com.kuros.kurosbackend.feed.redis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
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

    /**
     * 复合 member 的分隔符：{@code authorId + SEPARATOR + postId}。
     *
     * 为什么用冒号：用户 ID 与帖子 ID 都是 UUID（十六进制 + 连字符），
     * **不含冒号**——所以冒号不会与 ID 本身的内容冲突，可以安全地一刀切开。
     * 换任何 UUID 里可能出现的字符（连字符、数字、字母）都会让拆分产生歧义。
     */
    private static final String AUTHOR_SEPARATOR = ":";

    /**
     * 按作者清理 timeline 时的前缀匹配模式：{@code authorId + SEPARATOR + 任意}。
     *
     * 为什么必须带上结尾的分隔符做前缀：只用 {@code authorId} 开头匹配会误伤
     * 「id 以它开头」的其他作者（如 author-x 会命中 author-x-long 的帖子）。
     * 带上分隔符后，匹配的是完整的「作者段 + 冒号」边界。
     */
    private static final String AUTHOR_PREFIX_PATTERN = AUTHOR_SEPARATOR + "*";

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
     * member 格式是 {@code authorId:postId} 复合键（见 {@link #AUTHOR_SEPARATOR}）。
     * 为什么必须带作者：取消关注时要能删掉「该作者的所有帖子」，而 ZSet 的
     * member 此前只有 postId——没有作者信息就只能反查作者发过哪些帖再逐条删，
     * 又慢又易漏。带上作者后，同前缀的 member 即该作者的全部帖子。
     *
     * @param postId      帖子 ID
     * @param authorId    作者 ID（复合 member 的作者段）
     * @param publishedAt 发布时间（作为 ZSet score）
     * @param followerIds 粉丝用户 ID 列表
     */
    public void pushToTimelines(String postId, String authorId, LocalDateTime publishedAt, List<String> followerIds) {
        if (followerIds == null || followerIds.isEmpty()) return;
        double score = toEpochMillis(publishedAt);
        String member = encodeMember(authorId, postId);
        // Pipeline 批量：一次 RTT 完成所有粉丝的 ZADD + 裁剪 + TTL 刷新
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            byte[] memberBytes = member.getBytes();
            for (String followerId : followerIds) {
                byte[] keyBytes = timelineKey(followerId).getBytes();
                // ZADD（如果 member 已存在则更新 score——同一帖子不会重复推，但防御性处理）
                connection.zSetCommands().zAdd(keyBytes, score, memberBytes);
                // 裁剪：保留 score 最高的 maxSize 条（删掉最旧的）
                connection.zSetCommands().zRemRange(keyBytes, 0, -(maxSize + 1));
                // 刷新 TTL
                connection.keyCommands().expire(keyBytes, ttlSeconds);
            }
            return null; // Pipeline callback 要求返回 null
        });
    }

    /**
     * 从某用户的 timeline 里删掉某个作者的全部帖子（取消关注时调用）。
     *
     * 为什么不是逐条 ZREM：取消关注一次可能要删几十条（对方的历史帖子），
     * 逐条就是几十次 RTT。先 ZSCAN 按前缀捞出成员、再一次 Pipeline 批量删除，
     * 把网络往返压到常数次。
     *
     * 为什么用 ZSCAN 而不是 ZRANGEBYLEX：ZSET 的字典序范围查询要求成员按
     * score 之外的**同一套字典序**连续排列，而 {@code authorId:postId} 的
     * 成员在 ZSet 里是按 score 组织的——同作者的帖子 score 各不相同，
     * 在序列里并不相邻，ZRANGEBYLEX 的前提不成立。ZSCAN 是游标式全量遍历，
     * 按前缀过滤，语义上就对了。
     *
     * 为什么删空后不 DEL key：留着让 TTL 自然过期。删 key 与并发的 push
     * （另一个作者正在发帖）之间存在竞态——刚 DEL 掉，push 又把它建回来，
     * 这条新帖子的 TTL 是新的，行为反而难推。空 key 不占查询成本
     * （ZCARD 返回 0），等 TTL 回收即可。
     *
     * @return 实际删除的 member 数（调用方可据此判断是否真的清理了）
     */
    public long removeByAuthor(String userId, String authorId) {
        String key = timelineKey(userId);
        String prefix = authorId + AUTHOR_PREFIX_PATTERN;
        // ZSCAN 游标遍历，把该作者的 member 全部收集起来
        List<String> toDelete = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match(prefix).count(100).build();
        try (Cursor<ZSetOperations.TypedTuple<String>> cursor =
                     redisTemplate.opsForZSet().scan(key, options)) {
            while (cursor.hasNext()) {
                ZSetOperations.TypedTuple<String> tuple = cursor.next();
                if (tuple.getValue() != null) {
                    toDelete.add(tuple.getValue());
                }
            }
        }
        if (toDelete.isEmpty()) {
            return 0;
        }
        // 一次 Pipeline 批量删除（ZREM 支持多 member）
        Long removed = redisTemplate.opsForZSet().remove(key, toDelete.toArray());
        return removed == null ? 0 : removed;
    }

    /**
     * 读 timeline：按时间倒序取 offset..offset+limit-1 的 postId 列表。
     *
     * ZREVRANGE 语义：index 0 = score 最高（最新发布）；index -1 = score 最低（最旧）。
     * 所以 ZREVRANGE(offset, offset+limit-1) 就是"跳过 offset 个最新的，取接下来 limit 个"。
     *
     * 返回前把复合 member 解码成 postId——调用方（FeedService）要的是帖子 ID，
     * 不该感知存储格式。
     */
    public List<String> readTimeline(String userId, int offset, int limit) {
        String key = timelineKey(userId);
        Set<String> members = redisTemplate.opsForZSet().reverseRange(key, offset, (long) offset + limit - 1);
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        return members.stream().map(FeedTimelineStore::decodePostId).toList();
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
     * 按时间倒序（score DESC，同分 postId DESC）的最多 limit 个条目。
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
     * **复合 member 下同分比较必须先 decode**：member 是 {@code authorId:postId}，
     * 若直接按 member 字典序比较，排的是「作者 ID + 冒号 + 帖子 ID」——作者段会
     * 插在中间打乱顺序，同分翻页的边界判断跟着错。解码成纯 postId 后再比较，
     * 才与 (score, postId) 的全序定义一致。
     *
     * **为什么返回条目（postId + score）而不只是 postId 列表**：
     * ZREVRANGEBYSCORE ... WITHSCORES 已经把 score 带回来了，下一页游标需要的
     * 正是它。若只返回 postId，调用方得再发一次 ZSCORE 去要 score——而 ZSCORE
     * 要按复合 member 查，调用方（FeedService）手里只有解码后的 postId，
     * 根本没有作者段，只能再扫一遍。把已经拿到的 score 一起返回，
     * 既省一次往返，也避免「调用方拼不出 member」这个不可能优雅解决的接口问题。
     *
     * @param cursorScore 游标分数（上一页最后一条的 score）；null 表示第一页（从最新起）
     * @param cursorPostId 游标 postId（同分兜底）；null 表示第一页
     * @param limit       本页最多返回条数
     */
    public List<TimelineEntry> readTimelineByCursor(String userId, Long cursorScore, String cursorPostId, int limit) {
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
        List<TimelineEntry> page = new ArrayList<>(limit);
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            String member = tuple.getValue();
            Double score = tuple.getScore();
            if (member == null || score == null) {
                continue;
            }
            // 先解码再比较：member 是 authorId:postId，同分边界必须按 postId 判
            String postId = decodePostId(member);
            // 跳过游标项本身及同分块中「已返回」（postId >= cursorPostId）的边界成员
            if (!firstPage && score == max && postId.compareTo(cursorPostId) >= 0) {
                continue;
            }
            page.add(new TimelineEntry(postId, score.longValue()));
            if (page.size() == limit) {
                break;
            }
        }
        return page;
    }

    /** timeline 里的一条记录：帖子 ID + 它在 ZSet 中的 score（下一页游标要用）。 */
    public record TimelineEntry(String postId, long score) {
    }

    /**
     * 清空某用户的 timeline（测试用 @BeforeEach + 取消关注清理）。
     */
    public void clear(String userId) {
        redisTemplate.delete(timelineKey(userId));
    }

    /**
     * 检查某帖子是否在某用户的 timeline 里（测试断言用）。
     *
     * 为什么不能只按 postId 查：member 是 {@code authorId:postId} 复合键，
     * ZSCORE 按精确 member 匹配，传纯 postId 永远查不到。但测试场景里
     * （「这条帖在不在 timeline」）调用方只有 postId，所以这里用 SCAN 按
     * **后缀**匹配——复合 member 的结尾正是 {@code :postId}，能同时命中
     * 复合格式与旧格式（旧格式 member 就是 postId 本身，结尾也匹配）。
     *
     * 只用于测试断言，不在热路径上（生产读路径走 readTimeline）。
     */
    public boolean contains(String userId, String postId) {
        String key = timelineKey(userId);
        ScanOptions options = ScanOptions.scanOptions().match("*" + AUTHOR_SEPARATOR + postId).count(100).build();
        try (Cursor<ZSetOperations.TypedTuple<String>> cursor =
                     redisTemplate.opsForZSet().scan(key, options)) {
            return cursor.hasNext();
        }
    }

    // ---------- 复合 member 的编码/解码 ----------

    /**
     * 编码：{@code authorId + ":" + postId}。
     *
     * 为什么把作者放在前面而不是后面：清理时要按「作者前缀」扫描（见
     * {@link #removeByAuthor}），前缀匹配天然要求作者段在最前。
     */
    private static String encodeMember(String authorId, String postId) {
        return authorId + AUTHOR_SEPARATOR + postId;
    }

    /**
     * 解码 member → postId。
     *
     * **存量兼容的关键兜底**：改造前写入的 member 是纯 postId（不含分隔符）。
     * Redis 里的已有数据不会自动变形，若这里对无分隔符的 member 抛异常或
     * 返回 null，所有老用户的关注流会在升级后瞬间变空。策略：不含冒号的
     * member 直接整体当作 postId 返回——它本来就只是 postId。
     *
     * 为什么用第一个冒号切而不是最后一个：作者段是 UUID（不含冒号），
     * postId 也是 UUID（不含冒号），正常情况下只有一个冒号。用第一个切，
     * 即使将来某一端的 ID 格式变化带了冒号，作者段仍能正确取出，
     * 不会把作者段的后半吞进 postId。
     */
    private static String decodePostId(String member) {
        int separatorAt = member.indexOf(AUTHOR_SEPARATOR);
        return separatorAt < 0 ? member : member.substring(separatorAt + 1);
    }

    private String timelineKey(String userId) {
        return KEY_PREFIX + userId;
    }

    private static double toEpochMillis(LocalDateTime dateTime) {
        return dateTime.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}
