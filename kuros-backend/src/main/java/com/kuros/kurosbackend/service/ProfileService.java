package com.kuros.kurosbackend.service;

import com.kuros.kurosbackend.api.PageMeta;
import com.kuros.kurosbackend.api.PageResult;
import com.kuros.kurosbackend.api.ProfileCommentResponse;
import com.kuros.kurosbackend.api.ProfileOverviewResponse;
import com.kuros.kurosbackend.api.PublicProfileResponse;
import com.kuros.kurosbackend.domain.CommentStatus;
import com.kuros.kurosbackend.domain.CommunityComment;
import com.kuros.kurosbackend.domain.CommunityPost;
import com.kuros.kurosbackend.domain.CommunityUser;
import com.kuros.kurosbackend.domain.PostStatus;
import com.kuros.kurosbackend.exception.ResourceNotFoundException;
import com.kuros.kurosbackend.repository.CommunityCommentRepository;
import com.kuros.kurosbackend.repository.CommunityPostRepository;
import com.kuros.kurosbackend.repository.CommunityUserRepository;
import com.kuros.kurosbackend.repository.PostFavoriteRepository;
import com.kuros.kurosbackend.repository.UserFollowRepository;
import org.springframework.data.domain.Page;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class ProfileService {

    private static final int MAX_PAGE_SIZE = 50;

    private final CommunityUserRepository userRepository;
    private final CommunityPostRepository postRepository;
    private final CommunityCommentRepository commentRepository;
    private final CommunityPostService postService;
    private final PostFavoriteRepository favoriteRepository;
    private final UserFollowRepository followRepository;

    public ProfileService(
            CommunityUserRepository userRepository,
            CommunityPostRepository postRepository,
            CommunityCommentRepository commentRepository,
            CommunityPostService postService,
            PostFavoriteRepository favoriteRepository,
            UserFollowRepository followRepository
    ) {
        this.userRepository = userRepository;
        this.postRepository = postRepository;
        this.commentRepository = commentRepository;
        this.postService = postService;
        this.favoriteRepository = favoriteRepository;
        this.followRepository = followRepository;
    }

    // @Cacheable：用户公开资料查询缓存。
    // 用户资料页是高频访问路径，包含帖子数和点赞数的聚合查询，缓存可显著减少 DB 压力。
    @Cacheable(cacheNames = "publicProfile", key = "#userId")
    public PublicProfileResponse findPublic(String userId) {
        CommunityUser user = findUser(userId);
        return toPublic(user);
    }

    public PageResult<com.kuros.kurosbackend.api.PostSummaryResponse> findPublicPosts(String userId, int page, int pageSize) {
        findUser(userId);
        return postService.findPublishedByAuthor(userId, page, pageSize);
    }

    public ProfileOverviewResponse findOwn(String userId, int page, int pageSize) {
        CommunityUser user = findUser(userId);
        PageResult<com.kuros.kurosbackend.api.PostSummaryResponse> posts = postService.findPublishedByAuthor(userId, page, pageSize);
        PageResult<ProfileCommentResponse> comments = findOwnComments(userId, page, pageSize);
        PageResult<com.kuros.kurosbackend.api.PostSummaryResponse> favorites = postService.findPublishedByIds(favoriteRepository.findVisiblePostIds(userId, PostStatus.PUBLISHED, pageRequest(page, pageSize)));
        PageResult<PublicProfileResponse> following = findFollowing(userId, page, pageSize);
        PageResult<PublicProfileResponse> fans = findFans(userId, page, pageSize);
        long postCount = postService.publishedPostCount(userId);
        long likeCount = postService.publishedPostLikeCount(userId);
        return new ProfileOverviewResponse(
                toPublic(user),
                new ProfileOverviewResponse.ProfileStats(postCount, likeCount, comments.meta().totalItems()),
                posts,
                comments,
                favorites,
                following,
                fans
        );
    }

    private PageResult<PublicProfileResponse> findFollowing(String userId, int page, int pageSize) {
        Page<com.kuros.kurosbackend.domain.UserFollow> follows = followRepository.findByFollowerIdOrderByCreatedAtDesc(userId, pageRequest(page, pageSize));
        java.util.Map<String, CommunityUser> usersById = userRepository.findAllById(follows.getContent().stream().map(com.kuros.kurosbackend.domain.UserFollow::getFollowedId).toList())
                .stream().collect(java.util.stream.Collectors.toMap(CommunityUser::getId, user -> user));
        List<PublicProfileResponse> items = follows.getContent().stream().map(com.kuros.kurosbackend.domain.UserFollow::getFollowedId).map(usersById::get).filter(java.util.Objects::nonNull).map(this::toPublic).toList();
        return new PageResult<>(items, new PageMeta(follows.getNumber() + 1, follows.getSize(), follows.getTotalElements(), follows.getTotalPages()));
    }

    private PageResult<PublicProfileResponse> findFans(String userId, int page, int pageSize) {
        Page<com.kuros.kurosbackend.domain.UserFollow> follows = followRepository.findByFollowedIdOrderByCreatedAtDesc(userId, pageRequest(page, pageSize));
        java.util.Map<String, CommunityUser> usersById = userRepository.findAllById(follows.getContent().stream().map(com.kuros.kurosbackend.domain.UserFollow::getFollowerId).toList())
                .stream().collect(java.util.stream.Collectors.toMap(CommunityUser::getId, user -> user));
        List<PublicProfileResponse> items = follows.getContent().stream().map(com.kuros.kurosbackend.domain.UserFollow::getFollowerId).map(usersById::get).filter(java.util.Objects::nonNull).map(this::toPublic).toList();
        return new PageResult<>(items, new PageMeta(follows.getNumber() + 1, follows.getSize(), follows.getTotalElements(), follows.getTotalPages()));
    }

    private Pageable pageRequest(int page, int pageSize) {
        int normalizedPage = Math.max(page, 1);
        int normalizedPageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        return PageRequest.of(normalizedPage - 1, normalizedPageSize);
    }

    private PageResult<ProfileCommentResponse> findOwnComments(String userId, int page, int pageSize) {
        int normalizedPage = Math.max(page, 1);
        int normalizedPageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(normalizedPage - 1, normalizedPageSize, Sort.by(Sort.Order.desc("createdAt")));
        Page<CommunityComment> comments = commentRepository.findByAuthorId(userId, pageable);
        List<CommunityPost> posts = postRepository.findAllById(comments.getContent().stream().map(CommunityComment::getPostId).toList());
        java.util.Map<String, CommunityPost> postsById = posts.stream()
                .collect(java.util.stream.Collectors.toMap(CommunityPost::getId, post -> post));
        List<ProfileCommentResponse> items = comments.getContent().stream()
                .map(comment -> toComment(comment, postsById.get(comment.getPostId())))
                .toList();
        return new PageResult<>(items, new PageMeta(normalizedPage, normalizedPageSize, comments.getTotalElements(), comments.getTotalPages()));
    }

    private ProfileCommentResponse toComment(CommunityComment comment, CommunityPost post) {
        boolean deleted = comment.getStatus() == CommentStatus.DELETED;
        return new ProfileCommentResponse(
                comment.getId(), comment.getPostId(), post == null || post.getStatus() == PostStatus.DELETED ? "帖子已删除" : post.getTitle(), comment.getParentId(),
                deleted ? "该评论已删除" : comment.getContent(), deleted, comment.getCreatedAt()
        );
    }

    private PublicProfileResponse toPublic(CommunityUser user) {
        return new PublicProfileResponse(
                user.getId(), user.getNickname(), user.getAvatarUrl(), user.getBio(),
                postService.publishedPostCount(user.getId()), postService.publishedPostLikeCount(user.getId())
        );
    }

    private CommunityUser findUser(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("用户不存在"));
    }
}
