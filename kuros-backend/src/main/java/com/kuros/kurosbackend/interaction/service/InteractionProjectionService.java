package com.kuros.kurosbackend.interaction.service;

import com.kuros.kurosbackend.interaction.domain.PostFavorite;
import com.kuros.kurosbackend.interaction.api.PostInteractionResponse;
import com.kuros.kurosbackend.interaction.domain.PostFavoriteId;
import com.kuros.kurosbackend.interaction.domain.PostLike;
import com.kuros.kurosbackend.interaction.domain.PostLikeId;
import com.kuros.kurosbackend.interaction.event.InteractionEvent;
import com.kuros.kurosbackend.interaction.redis.InteractionRedisStore;
import com.kuros.kurosbackend.interaction.repository.PostFavoriteRepository;
import com.kuros.kurosbackend.interaction.repository.PostLikeRepository;
import com.kuros.kurosbackend.post.repository.CommunityPostRepository;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

@Service
public class InteractionProjectionService {

    private final PostLikeRepository likeRepository;
    private final PostFavoriteRepository favoriteRepository;
    private final CommunityPostRepository postRepository;
    private final TransactionTemplate transactionTemplate;
    private final InteractionRedisStore redisStore;
    private final JdbcTemplate jdbc;

    public InteractionProjectionService(PostLikeRepository likeRepository,
                                        PostFavoriteRepository favoriteRepository,
                                        CommunityPostRepository postRepository,
                                        TransactionTemplate transactionTemplate,
                                        InteractionRedisStore redisStore,
                                        JdbcTemplate jdbc) {
        this.likeRepository = likeRepository;
        this.favoriteRepository = favoriteRepository;
        this.postRepository = postRepository;
        this.transactionTemplate = transactionTemplate;
        this.redisStore = redisStore;
        this.jdbc = jdbc;
    }

    public void apply(InteractionEvent event) {
        transactionTemplate.executeWithoutResult(status -> persist(event));
        // 重投也要失效缓存，前一次可能在提交之后、失效之前失败。
        redisStore.invalidate(event.type().kind(), event.postId(), event.userId());
        jdbc.update("update interaction_operations set cache_pending = false where operation_id = ?", event.eventId());
        jdbc.update("update interaction_failures set status = 'RESOLVED', updated_at = ? where operation_id = ? and post_id = ? and user_id = ? and operation_type = ?",
                LocalDateTime.now(), event.eventId(), event.postId(), event.userId(), event.type().name());
    }

    public boolean isFailurePaused(String operationId) {
        return !jdbc.query("select operation_id from interaction_failures where operation_id = ? and status = 'PAUSED'",
                (rs, row) -> rs.getString(1), operationId).isEmpty();
    }

    public boolean isPostBlocked(String postId) {
        return !jdbc.query("select operation_id from interaction_failures where post_id = ? and status = 'PAUSED'",
                (rs, row) -> rs.getString(1), postId).isEmpty();
    }

    public boolean isApplied(InteractionEvent event) {
        return !jdbc.query("select operation_id from interaction_operations where operation_id = ? and post_id = ? and user_id = ? and operation_type = ?",
                (rs, row) -> rs.getString(1), event.eventId(), event.postId(), event.userId(), event.type().name()).isEmpty();
    }

    public void requireCompatible(InteractionEvent event) {
        var previous = jdbc.query("select post_id, user_id, operation_type from interaction_operations where operation_id = ?",
                (rs, row) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}, event.eventId());
        if (!previous.isEmpty()) {
            String[] operation = previous.getFirst();
            if (!event.postId().equals(operation[0]) || !event.userId().equals(operation[1]) || !event.type().name().equals(operation[2])) {
                throw new com.kuros.kurosbackend.shared.exception.AuthRequestException("INTERACTION_OPERATION_CONFLICT", "操作编号已绑定其他互动");
            }
        }
    }

    public record Failure(String operationId, String postId, String userId, String type, long occurredAt,
                          String errorType, String status) { }

    public java.util.List<Failure> pendingFailures() {
        return jdbc.query("select operation_id, post_id, user_id, operation_type, occurred_at, error_type, status from interaction_failures where status <> 'RESOLVED' order by updated_at limit 100",
                (rs, row) -> new Failure(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getLong(5), rs.getString(6), rs.getString(7)));
    }

    public void recordFailure(InteractionEvent event, String errorType) {
        int changed = jdbc.update("update interaction_failures set status = 'PAUSED', error_type = ?, updated_at = ? where operation_id = ?",
                errorType, LocalDateTime.now(), event.eventId());
        if (changed == 0) {
            jdbc.update("insert into interaction_failures (operation_id, post_id, user_id, operation_type, occurred_at, error_type, status, updated_at) values (?, ?, ?, ?, ?, ?, 'PAUSED', ?)",
                    event.eventId(), event.postId(), event.userId(), event.type().name(), event.occurredAt(), errorType, LocalDateTime.now());
        }
    }

    public void resumeFailure(String operationId) {
        var events = jdbc.query("select post_id, user_id, operation_type, occurred_at from interaction_failures where operation_id = ? and status = 'PAUSED'",
                (rs, row) -> new InteractionEvent(operationId, rs.getString(1), rs.getString(2),
                        com.kuros.kurosbackend.interaction.event.InteractionType.valueOf(rs.getString(3)), rs.getLong(4)), operationId);
        if (events.isEmpty()) {
            throw new com.kuros.kurosbackend.shared.exception.ResourceNotFoundException("INTERACTION_FAILURE_NOT_FOUND", "待恢复任务不存在");
        }
        apply(events.getFirst());
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${app.interaction.cache-recovery-delay-ms:30000}")
    public void recoverPending() {
        var pending = jdbc.query("select operation_id, post_id, user_id, operation_type from interaction_operations where cache_pending = true order by applied_at limit 100",
                (rs, row) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)});
        for (String[] operation : pending) {
            redisStore.invalidate(com.kuros.kurosbackend.interaction.event.InteractionType.valueOf(operation[3]).kind(), operation[1], operation[2]);
            jdbc.update("update interaction_operations set cache_pending = false where operation_id = ?", operation[0]);
            jdbc.update("update interaction_failures set status = 'RESOLVED', updated_at = ? where operation_id = ? and post_id = ? and user_id = ? and operation_type = ?",
                    LocalDateTime.now(), operation[0], operation[1], operation[2], operation[3]);
        }
    }

    public record OperationResult(String status, PostInteractionResponse state) { }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public OperationResult findOperation(String operationId, String userId) {
        var posts = jdbc.query("select post_id from interaction_operations where operation_id = ? and user_id = ?",
                (rs, row) -> rs.getString(1), operationId, userId);
        if (posts.isEmpty()) {
            return new OperationResult("UNKNOWN", null);
        }
        String postId = posts.getFirst();
        var post = postRepository.findById(postId).orElseThrow();
        return new OperationResult("APPLIED", new PostInteractionResponse(postId, post.getLikeCount(),
                post.getFavoriteCount(), likeRepository.existsById(new PostLikeId(userId, postId)),
                favoriteRepository.existsById(new PostFavoriteId(userId, postId))));
    }

    private void persist(InteractionEvent event) {
        String postId = event.postId();
        String userId = event.userId();
        jdbc.queryForObject("select id from posts where id = ? for update", String.class, postId);
        var previous = jdbc.query("select post_id, user_id, operation_type from interaction_operations where operation_id = ?",
                (rs, row) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}, event.eventId());
        if (!previous.isEmpty()) {
            String[] operation = previous.getFirst();
            if (!postId.equals(operation[0]) || !userId.equals(operation[1]) || !event.type().name().equals(operation[2])) {
                throw new IllegalArgumentException("Operation ID is bound to a different interaction");
            }
            return;
        }
        jdbc.update("insert into interaction_operations (operation_id, post_id, user_id, operation_type, applied_at) values (?, ?, ?, ?, ?)",
                event.eventId(), postId, userId, event.type().name(), LocalDateTime.now());
        LocalDateTime now = LocalDateTime.now();
        switch (event.type()) {
            case LIKE -> {
                PostLikeId id = new PostLikeId(userId, postId);
                if (!likeRepository.existsById(id)) {
                    likeRepository.save(new PostLike(userId, postId, now));
                    postRepository.incrementLikeCount(postId);
                }
            }
            case UNLIKE -> {
                PostLikeId id = new PostLikeId(userId, postId);
                if (likeRepository.existsById(id)) {
                    likeRepository.deleteById(id);
                    postRepository.decrementLikeCount(postId);
                }
            }
            case FAVORITE -> {
                PostFavoriteId id = new PostFavoriteId(userId, postId);
                if (!favoriteRepository.existsById(id)) {
                    favoriteRepository.save(new PostFavorite(userId, postId, now));
                    postRepository.incrementFavoriteCount(postId);
                }
            }
            case UNFAVORITE -> {
                PostFavoriteId id = new PostFavoriteId(userId, postId);
                if (favoriteRepository.existsById(id)) {
                    favoriteRepository.deleteById(id);
                    postRepository.decrementFavoriteCount(postId);
                }
            }
        }
    }
}
