package com.kuros.kurosbackend.user.service;

import com.kuros.kurosbackend.comment.domain.CommentStatus;
import com.kuros.kurosbackend.comment.domain.CommunityComment;
import com.kuros.kurosbackend.comment.repository.CommunityCommentRepository;
import com.kuros.kurosbackend.post.api.PostSummaryResponse;
import com.kuros.kurosbackend.post.domain.CommunityPost;
import com.kuros.kurosbackend.post.domain.PostStatus;
import com.kuros.kurosbackend.post.repository.CommunityPostRepository;
import com.kuros.kurosbackend.post.service.CommunityPostService;
import com.kuros.kurosbackend.interaction.repository.PostFavoriteRepository;
import com.kuros.kurosbackend.shared.api.PageMeta;
import com.kuros.kurosbackend.shared.api.PageResult;
import com.kuros.kurosbackend.shared.cache.CacheNames;
import com.kuros.kurosbackend.shared.cache.TwoLevelCache;
import com.kuros.kurosbackend.user.api.ProfileCommentResponse;
import com.kuros.kurosbackend.user.api.ProfileOverviewResponse;
import com.kuros.kurosbackend.user.api.PublicProfileResponse;
import com.kuros.kurosbackend.user.client.UserBriefDto;
import com.kuros.kurosbackend.user.client.UserDirectoryFacade;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户资料服务（split-08：从"窗口期整端 503"重建为"跨服务组合视图"）。
 *
 * 数据源拆分（本切片的核心决策，Q11-A）：
 * - 用户资料字段（nickname / avatarUrl / bio）与关注关系（following / fans）→ 经 Feign 读 kuros-user
 * - 内容统计（postCount / likeCount / commentCount）与帖子 / 评论 / 收藏列表 → 仍在本库
 *   （posts / comments / post_favorites 表未迁走，author_id 是裸外键，本地聚合照常可算）
 * 组合视图刻意不进 kuros-user（那会让用户域反向依赖内容域）——依赖保持单向：内容域 → 用户域。
 *
 * 降级策略（工单 #3）：资料页强依赖用户资料，Feign 失败即 503（见 UserDirectoryFacade.requireUser）；
 * 这与"列表降级为占位作者、照常 200"相反——半截资料比明确的 503 更难被前端消费。
 */
@Service
@Transactional(readOnly = true)
public class ProfileService {

    private static final int MAX_PAGE_SIZE = 50;

    private final CommunityPostRepository postRepository;
    private final CommunityCommentRepository commentRepository;
    private final CommunityPostService postService;
    private final PostFavoriteRepository favoriteRepository;
    private final UserDirectoryFacade userDirectory;
    private final TwoLevelCache twoLevelCache;

    public ProfileService(
            CommunityPostRepository postRepository,
            CommunityCommentRepository commentRepository,
            CommunityPostService postService,
            PostFavoriteRepository favoriteRepository,
            UserDirectoryFacade userDirectory,
            TwoLevelCache twoLevelCache
    ) {
        this.postRepository = postRepository;
        this.commentRepository = commentRepository;
        this.postService = postService;
        this.favoriteRepository = favoriteRepository;
        this.userDirectory = userDirectory;
        this.twoLevelCache = twoLevelCache;
    }

    /**
     * 公开资料：GET /api/v1/users/{userId}（资料页头部）。
     * 用户字段经 Feign 读 kuros-user，postCount / likeCount 本地聚合。
     *
     * 切片 #13：publicProfile 纳入两级缓存——L2 Redis 省一次 Feign 往返、且跨重启共享（冷启动不反复打 kuros-user）。
     * 失效策略：昵称/头像来自 kuros-user，本服务无跨服务事件通道（用户在彼端改资料本端无从感知），
     * 故靠 L1 10s + L2 60s TTL 兜底最终一致（最长 60s 收敛）；生产化需「改资料即失效」的强一致时，
     * 须接入事件驱动失效（留待 RocketMQ 切片：kuros-user 发用户变更事件 → backend 消费后驱逐对应键）。
     */
    public PublicProfileResponse findPublic(String userId) {
        return twoLevelCache.get(CacheNames.PUBLIC_PROFILE, userId, PublicProfileResponse.class,
                () -> toPublic(userDirectory.requireUser(userId)));
    }

    /**
     * 按作者查已发布帖子：GET /api/v1/users/{userId}/posts。
     *
     * 刻意不前置 requireUser（与 split-07 前旧实现不同）：这是"列表"语义，
     * 按工单 #3 列表在用户域不可用时也要照常返回——帖子本库自持，作者昵称由
     * CommunityPostService 走批量降级（命中即真实、否则占位），无需为列表引入 503/404 失败面。
     * 未知作者 ID 自然命中空列表（meta.totalItems=0），对调用方语义不退化。
     */
    public PageResult<PostSummaryResponse> findPublicPosts(String userId, int page, int pageSize) {
        return postService.findPublishedByAuthor(userId, page, pageSize);
    }

    /**
     * 个人中心聚合：GET /api/v1/users/me/profile。
     * 用户资料 + following/fans 经 Feign；posts/comments/favorites + 统计本地算。
     * requireUser 失败即 503（资料页强依赖用户资料，不做半截降级）。
     */
    public ProfileOverviewResponse findOwn(String userId, int page, int pageSize) {
        UserBriefDto user = userDirectory.requireUser(userId);
        PageResult<PostSummaryResponse> posts = postService.findPublishedByAuthor(userId, page, pageSize);
        PageResult<ProfileCommentResponse> comments = findOwnComments(userId, page, pageSize);
        PageResult<PostSummaryResponse> favorites = postService.findPublishedByIds(
                favoriteRepository.findVisiblePostIds(userId, PostStatus.PUBLISHED, pageRequest(page, pageSize)));
        PageResult<PublicProfileResponse> following = toPublicPage(userDirectory.following(userId, page, pageSize));
        PageResult<PublicProfileResponse> fans = toPublicPage(userDirectory.followers(userId, page, pageSize));
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

    /**
     * 关注/粉丝分页：Feign 返回的是 UserBriefDto（无本地内容统计），
     * 这里逐个补上本地 postCount/likeCount 组装成 PublicProfileResponse——
     * 被关注用户的帖子仍在本库，本地聚合可算，无需二次跨服务。
     */
    private PageResult<PublicProfileResponse> toPublicPage(PageResult<UserBriefDto> page) {
        List<PublicProfileResponse> items = page.items().stream().map(this::toPublic).toList();
        return new PageResult<>(items, page.meta());
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
        Map<String, CommunityPost> postsById = postRepository
                .findAllById(comments.getContent().stream().map(CommunityComment::getPostId).toList())
                .stream()
                .collect(Collectors.toMap(CommunityPost::getId, post -> post));
        List<ProfileCommentResponse> items = comments.getContent().stream()
                .map(comment -> toComment(comment, postsById.get(comment.getPostId())))
                .toList();
        return new PageResult<>(items, new PageMeta(normalizedPage, normalizedPageSize, comments.getTotalElements(), comments.getTotalPages()));
    }

    private ProfileCommentResponse toComment(CommunityComment comment, CommunityPost post) {
        boolean deleted = comment.getStatus() == CommentStatus.DELETED;
        return new ProfileCommentResponse(
                comment.getId(), comment.getPostId(),
                post == null || post.getStatus() == PostStatus.DELETED ? "帖子已删除" : post.getTitle(),
                comment.getParentId(),
                deleted ? "该评论已删除" : comment.getContent(), deleted, comment.getCreatedAt()
        );
    }

    /**
     * 用户字段来自 Feign 摘要，postCount/likeCount 来自本地聚合——
     * 这正是"被拆掉的 users JOIN posts"在应用层的重建。
     */
    private PublicProfileResponse toPublic(UserBriefDto user) {
        return new PublicProfileResponse(
                user.id(), user.nickname(), user.avatarUrl(), user.bio(),
                postService.publishedPostCount(user.id()), postService.publishedPostLikeCount(user.id())
        );
    }
}
