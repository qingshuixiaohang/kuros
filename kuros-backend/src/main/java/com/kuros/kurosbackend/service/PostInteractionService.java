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

import java.time.LocalDateTime;

@Service
@Transactional
public class PostInteractionService {

    private final CommunityPostRepository postRepository;
    private final PostLikeRepository likeRepository;
    private final com.kuros.kurosbackend.repository.PostFavoriteRepository favoriteRepository;

    public PostInteractionService(
            CommunityPostRepository postRepository,
            PostLikeRepository likeRepository,
            com.kuros.kurosbackend.repository.PostFavoriteRepository favoriteRepository
    ) {
        this.postRepository = postRepository;
        this.likeRepository = likeRepository;
        this.favoriteRepository = favoriteRepository;
    }

    @Transactional(readOnly = true)
    public PostInteractionResponse findPost(String postId, String userId) {
        CommunityPost post = findPublishedPost(postId);
        boolean liked = userId != null && likeRepository.existsById(new PostLikeId(userId, postId));
        boolean favorited = userId != null && favoriteRepository.existsById(new PostFavoriteId(userId, postId));
        return new PostInteractionResponse(postId, post.getLikeCount(), post.getFavoriteCount(), liked, favorited);
    }

    // 点赞改变 likeCount，必须驱逐详情缓存
    @CacheEvict(cacheNames = "postDetail", key = "#postId")
    public PostInteractionResponse like(String postId, String userId) {
        CommunityPost post = findPublishedPost(postId);
        PostLikeId id = new PostLikeId(userId, postId);
        if (!likeRepository.existsById(id)) {
            likeRepository.save(new PostLike(userId, postId, LocalDateTime.now()));
            postRepository.incrementLikeCount(postId);
        }
        return findPost(postId, userId);
    }

    @CacheEvict(cacheNames = "postDetail", key = "#postId")
    public PostInteractionResponse favorite(String postId, String userId) {
        findPublishedPost(postId);
        PostFavoriteId id = new PostFavoriteId(userId, postId);
        if (!favoriteRepository.existsById(id)) {
            favoriteRepository.save(new PostFavorite(userId, postId, LocalDateTime.now()));
            postRepository.incrementFavoriteCount(postId);
        }
        return findPost(postId, userId);
    }

    @CacheEvict(cacheNames = "postDetail", key = "#postId")
    public PostInteractionResponse unfavorite(String postId, String userId) {
        findPublishedPost(postId);
        PostFavoriteId id = new PostFavoriteId(userId, postId);
        if (favoriteRepository.existsById(id)) {
            favoriteRepository.deleteById(id);
            postRepository.decrementFavoriteCount(postId);
        }
        return findPost(postId, userId);
    }

    @CacheEvict(cacheNames = "postDetail", key = "#postId")
    public PostInteractionResponse unlike(String postId, String userId) {
        findPublishedPost(postId);
        PostLikeId id = new PostLikeId(userId, postId);
        if (likeRepository.existsById(id)) {
            likeRepository.deleteById(id);
            postRepository.decrementLikeCount(postId);
        }
        return findPost(postId, userId);
    }

    private CommunityPost findPublishedPost(String postId) {
        return postRepository.findByIdAndStatus(postId, PostStatus.PUBLISHED)
                .orElseThrow(() -> new ResourceNotFoundException("帖子不存在或已删除"));
    }
}
