package com.kuros.kurosbackend.interaction.service;

import com.kuros.kurosbackend.interaction.event.InteractionKind;
import com.kuros.kurosbackend.interaction.redis.InteractionRedisStore;
import com.kuros.kurosbackend.interaction.repository.PostFavoriteRepository;
import com.kuros.kurosbackend.interaction.repository.PostLikeRepository;
import com.kuros.kurosbackend.post.repository.CommunityPostRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 互动计数对账（审计 M5 / B3）。
 *
 * 为什么需要对账：互动计数有三份状态，而它们之间只有「最终一致」的承诺，
 * 没有任何兜底——
 *   1. Redis 计数（interaction:count:*）：写路径即时翻转，读路径实时源，TTL 24h
 *   2. posts.like_count / posts.favorite_count 冗余列：MQ 异步投影时增量更新
 *   3. post_likes / post_favorites 关系表：投影时增删行
 *
 * 失效路径：MQ 投递失败时有同步降级落库兜底，但降级也失败（DB 抖动、
 * 连接池耗尽）时事件就永久丢了。此时关系表和冗余列同时缺一条，而 Redis
 * 是对的；反过来若 Redis key 过期，TTL 到期后会按「可能已经错了的」冗余列
 * 回填，把偏差固化成真相。没有对账，这个偏差就永远没人知道。
 *
 * 为什么用关系表 COUNT(*) 作为真相，而不是读冗余列：
 * 冗余列是增量维护的产物，事件丢失会让它永久偏移；关系表的行数是幂等
 * 可重算的——同一批事件重复消费不会让行数变化。所以 COUNT(*) 是能
 * 自我修复的真相，冗余列只是读优化，对账时一并修正它。
 *
 * 事务纪律：DB 侧修正走 TransactionTemplate 自开自提交（与
 * InteractionProjectionService 同一套纪律），不与 Redis 操作混在
 * 同一个非事务方法里——两者无法共事务，混写只会让人误以为有原子性。
 */
@Service
public class InteractionCountReconciler {

    private static final Logger log = LoggerFactory.getLogger(InteractionCountReconciler.class);

    private final InteractionRedisStore redisStore;
    private final PostLikeRepository likeRepository;
    private final PostFavoriteRepository favoriteRepository;
    private final CommunityPostRepository postRepository;
    private final TransactionTemplate transactionTemplate;

    public InteractionCountReconciler(InteractionRedisStore redisStore,
                                      PostLikeRepository likeRepository,
                                      PostFavoriteRepository favoriteRepository,
                                      CommunityPostRepository postRepository,
                                      TransactionTemplate transactionTemplate) {
        this.redisStore = redisStore;
        this.likeRepository = likeRepository;
        this.favoriteRepository = favoriteRepository;
        this.postRepository = postRepository;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 对账单帖的点赞 + 收藏计数：以关系表为准，修正 Redis 与 DB 冗余列。
     *
     * @return 每个维度修正前后的值，便于调用方记录日志/上报监控
     */
    public Map<InteractionKind, CountDelta> reconcile(String postId) {
        Map<InteractionKind, CountDelta> deltas = new LinkedHashMap<>();
        deltas.put(InteractionKind.LIKE, reconcileLike(postId));
        deltas.put(InteractionKind.FAVORITE, reconcileFavorite(postId));
        return deltas;
    }

    private CountDelta reconcileLike(String postId) {
        long truth = likeRepository.countByPostId(postId);
        long redisBefore = readRedisCount(InteractionKind.LIKE, postId, truth);
        InteractionRedisStore.ReconcileResult result = redisStore.reconcileCount(
                InteractionKind.LIKE, postId, () -> truth);
        long dbBefore = transactionTemplate.execute(status ->
                postRepository.findById(postId).map(post -> post.getLikeCount()).orElse(truth));
        transactionTemplate.executeWithoutResult(status ->
                postRepository.updateLikeCount(postId, truth));

        CountDelta delta = new CountDelta(redisBefore, result.count(), dbBefore);
        logIfCorrected(postId, InteractionKind.LIKE, delta);
        return delta;
    }

    private CountDelta reconcileFavorite(String postId) {
        long truth = favoriteRepository.countByPostId(postId);
        long redisBefore = readRedisCount(InteractionKind.FAVORITE, postId, truth);
        InteractionRedisStore.ReconcileResult result = redisStore.reconcileCount(
                InteractionKind.FAVORITE, postId, () -> truth);
        long dbBefore = transactionTemplate.execute(status ->
                postRepository.findById(postId).map(post -> post.getFavoriteCount()).orElse(truth));
        transactionTemplate.executeWithoutResult(status ->
                postRepository.updateFavoriteCount(postId, truth));

        CountDelta delta = new CountDelta(redisBefore, result.count(), dbBefore);
        logIfCorrected(postId, InteractionKind.FAVORITE, delta);
        return delta;
    }

    /**
     * 读 Redis 现值用于记录「修正前」；key 不存在时以 truth 兜底
     * （此时 readCount 会回填，行为与读路径一致）。
     */
    private long readRedisCount(InteractionKind kind, String postId, long truth) {
        return redisStore.readCount(kind, postId, () -> truth);
    }

    private void logIfCorrected(String postId, InteractionKind kind, CountDelta delta) {
        if (delta.redisBefore() != delta.after() || delta.dbBefore() != delta.after()) {
            // WARN 级别：计数偏差意味着有事件丢了，是应该被发现的异常，
            // 而不是正常的最终一致窗口
            log.warn("互动计数对账修正：postId={} kind={} redis {} -> {}，dbColumn {} -> {}",
                    postId, kind, delta.redisBefore(), delta.after(), delta.dbBefore(), delta.after());
        }
    }

    /**
     * 一次对账前后各个计数位的值。
     *
     * @param redisBefore 修正前 Redis 计数
     * @param after       修正后的值（Redis 与 DB 列都等于它）
     * @param dbBefore    修正前 DB 冗余列
     */
    public record CountDelta(long redisBefore, long after, long dbBefore) {
    }
}
