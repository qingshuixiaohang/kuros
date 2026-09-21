package com.kuros.kurosbackend.service;

import com.kuros.kurosbackend.api.PostInteractionResponse;
import com.kuros.kurosbackend.domain.CommunityPost;
import com.kuros.kurosbackend.domain.PostLike;
import com.kuros.kurosbackend.domain.PostLikeId;
import com.kuros.kurosbackend.domain.PostFavorite;
import com.kuros.kurosbackend.domain.PostFavoriteId;
import com.kuros.kurosbackend.domain.PostStatus;
import com.kuros.kurosbackend.exception.ResourceNotFoundException;
import com.kuros.kurosbackend.repository.CommunityPostRepository;
import com.kuros.kurosbackend.repository.PostLikeRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

@Service
@Transactional
public class PostInteractionService {

    private final CommunityPostRepository postRepository;
    private final PostLikeRepository likeRepository;
    private final com.kuros.kurosbackend.repository.PostFavoriteRepository favoriteRepository;
    private final DistributedLock distributedLock;
    private final TransactionTemplate transactionTemplate;

    public PostInteractionService(
            CommunityPostRepository postRepository,
            PostLikeRepository likeRepository,
            com.kuros.kurosbackend.repository.PostFavoriteRepository favoriteRepository,
            DistributedLock distributedLock,
            TransactionTemplate transactionTemplate
    ) {
        this.postRepository = postRepository;
        this.likeRepository = likeRepository;
        this.favoriteRepository = favoriteRepository;
        this.distributedLock = distributedLock;
        this.transactionTemplate = transactionTemplate;
    }

    @Transactional(readOnly = true)
    public PostInteractionResponse findPost(String postId, String userId) {
        CommunityPost post = findPublishedPost(postId);
        boolean liked = userId != null && likeRepository.existsById(new PostLikeId(userId, postId));
        boolean favorited = userId != null && favoriteRepository.existsById(new PostFavoriteId(userId, postId));
        return new PostInteractionResponse(postId, post.getLikeCount(), post.getFavoriteCount(), liked, favorited);
    }

    // 分布式锁必须包裹事务：lock → [tx begin → 业务 → tx commit] → unlock
    // 不能用 @Transactional 方法级注解，否则锁在事务内部释放，存在竞态窗口
    @CacheEvict(cacheNames = "postDetail", key = "#postId")
    public PostInteractionResponse like(String postId, String userId) {
        String lockValue = distributedLock.tryLock("lock:like:" + postId + ":" + userId, 3);
        if (lockValue == null) return findPost(postId, userId);
        try {
            return transactionTemplate.execute(status -> {
                CommunityPost post = findPublishedPost(postId);
                PostLikeId id = new PostLikeId(userId, postId);
                if (!likeRepository.existsById(id)) {
                    likeRepository.save(new PostLike(userId, postId, LocalDateTime.now()));
                    postRepository.incrementLikeCount(postId);
                }
                return findPost(postId, userId);
            });
        } finally {
            distributedLock.unlock("lock:like:" + postId + ":" + userId, lockValue);
        }
    }

    @CacheEvict(cacheNames = "postDetail", key = "#postId")
    public PostInteractionResponse favorite(String postId, String userId) {
        String lockValue = distributedLock.tryLock("lock:favorite:" + postId + ":" + userId, 3);
        if (lockValue == null) return findPost(postId, userId);
        try {
            return transactionTemplate.execute(status -> {
                findPublishedPost(postId);
                PostFavoriteId id = new PostFavoriteId(userId, postId);
                if (!favoriteRepository.existsById(id)) {
                    favoriteRepository.save(new PostFavorite(userId, postId, LocalDateTime.now()));
                    postRepository.incrementFavoriteCount(postId);
                }
                return findPost(postId, userId);
            });
        } finally {
            distributedLock.unlock("lock:favorite:" + postId + ":" + userId, lockValue);
        }
    }

    // unlike/unfavorite 也需要锁，防止并发取消导致计数变负
    @CacheEvict(cacheNames = "postDetail", key = "#postId")
    public PostInteractionResponse unlike(String postId, String userId) {
        String lockValue = distributedLock.tryLock("lock:like:" + postId + ":" + userId, 3);
        if (lockValue == null) return findPost(postId, userId);
        try {
            return transactionTemplate.execute(status -> {
                findPublishedPost(postId);
                PostLikeId id = new PostLikeId(userId, postId);
                if (likeRepository.existsById(id)) {
                    likeRepository.deleteById(id);
                    postRepository.decrementLikeCount(postId);
                }
                return findPost(postId, userId);
            });
        } finally {
            distributedLock.unlock("lock:like:" + postId + ":" + userId, lockValue);
        }
    }

    @CacheEvict(cacheNames = "postDetail", key = "#postId")
    public PostInteractionResponse unfavorite(String postId, String userId) {
        String lockValue = distributedLock.tryLock("lock:favorite:" + postId + ":" + userId, 3);
        if (lockValue == null) return findPost(postId, userId);
        try {
            return transactionTemplate.execute(status -> {
                findPublishedPost(postId);
                PostFavoriteId id = new PostFavoriteId(userId, postId);
                if (favoriteRepository.existsById(id)) {
                    favoriteRepository.deleteById(id);
                    postRepository.decrementFavoriteCount(postId);
                }
                return findPost(postId, userId);
            });
        } finally {
            distributedLock.unlock("lock:favorite:" + postId + ":" + userId, lockValue);
        }
    }

    private CommunityPost findPublishedPost(String postId) {
        return postRepository.findByIdAndStatus(postId, PostStatus.PUBLISHED)
                .orElseThrow(() -> new ResourceNotFoundException("帖子不存在或已删除"));
    }
}
