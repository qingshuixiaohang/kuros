package com.kuros.kurosbackend.comment.service;

import com.kuros.kurosbackend.comment.api.CommentResponse;
import com.kuros.kurosbackend.comment.api.CreateCommentRequest;
import com.kuros.kurosbackend.shared.api.PageMeta;
import com.kuros.kurosbackend.shared.api.PageResult;
import com.kuros.kurosbackend.comment.domain.CommentStatus;
import com.kuros.kurosbackend.comment.domain.CommunityComment;
import com.kuros.kurosbackend.post.domain.CommunityPost;
import com.kuros.kurosbackend.post.domain.PostStatus;
import com.kuros.kurosbackend.shared.exception.AuthRequestException;
import com.kuros.kurosbackend.shared.exception.ForbiddenException;
import com.kuros.kurosbackend.shared.exception.ResourceNotFoundException;
import com.kuros.kurosbackend.comment.repository.CommunityCommentRepository;
import com.kuros.kurosbackend.post.repository.CommunityPostRepository;
import com.kuros.kurosbackend.shared.cache.CacheNames;
import com.kuros.kurosbackend.shared.cache.TwoLevelCache;
import com.kuros.kurosbackend.user.client.UserBriefDto;
import com.kuros.kurosbackend.user.client.UserDirectoryFacade;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CommunityCommentService {

    private static final int MAX_PAGE_SIZE = 50;

    private final CommunityCommentRepository commentRepository;
    private final CommunityPostRepository postRepository;
    private final UserDirectoryFacade userDirectory;
    private final TwoLevelCache twoLevelCache;

    public CommunityCommentService(
            CommunityCommentRepository commentRepository,
            CommunityPostRepository postRepository,
            UserDirectoryFacade userDirectory,
            TwoLevelCache twoLevelCache
    ) {
        this.commentRepository = commentRepository;
        this.postRepository = postRepository;
        this.userDirectory = userDirectory;
        this.twoLevelCache = twoLevelCache;
    }

    @Transactional(readOnly = true)
    public PageResult<CommentResponse> findByPost(String postId, int page, int pageSize, String sort) {
        ensurePublishedPost(postId);
        int normalizedPage = Math.max(page, 1);
        int normalizedPageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(normalizedPage - 1, normalizedPageSize, sortOf(sort));
        Page<CommunityComment> comments = commentRepository.findByPostId(postId, pageable);
        // split-08：整页评论作者一次批量回填（Feign），避免逐条单查的 N+1 跨服务调用
        Map<String, UserBriefDto> authors = userDirectory.findAuthors(
                comments.getContent().stream().map(CommunityComment::getAuthorId).toList());
        List<CommentResponse> items = comments.getContent().stream()
                .map(comment -> toResponse(comment, authors))
                .toList();
        return new PageResult<>(items, new PageMeta(normalizedPage, normalizedPageSize, comments.getTotalElements(), comments.getTotalPages()));
    }

    // 评论创建后驱逐帖子详情两级缓存（commentCount 是缓存内容字段），确保下次查询获取最新评论数。
    // 切片 #13：从 @CacheEvict 迁为 twoLevelCache.evict——注解只清 L1 Caffeine，清不掉 L2 Redis，会留下陈旧内容。
    @Transactional
    public CommentResponse create(String postId, String authorId, CreateCommentRequest request) {
        ensurePublishedPost(postId);
        String content = request == null || request.content() == null ? "" : request.content().trim();
        if (content.isEmpty() || content.length() > 1000) {
            throw new AuthRequestException("INVALID_COMMENT_CONTENT", "评论内容需为 1 到 1000 个字符");
        }
        String parentId = request == null ? null : blankToNull(request.parentId());
        if (parentId != null) {
            CommunityComment parent = commentRepository.findById(parentId)
                    .orElseThrow(() -> new AuthRequestException("COMMENT_PARENT_NOT_FOUND", "回复的评论不存在"));
            if (!postId.equals(parent.getPostId()) || parent.getParentId() != null) {
                throw new AuthRequestException("COMMENT_NESTING_NOT_ALLOWED", "目前只支持一级回复");
            }
        }
        // split-07：不再校验作者在本库存在（用户表已迁出）——
        // 能通过会话鉴权即为 kuros-user 认定的登录用户，无需本库二次确认
        LocalDateTime now = LocalDateTime.now();
        CommunityComment comment = commentRepository.save(new CommunityComment(
                UUID.randomUUID().toString(), postId, authorId, parentId, content, now
        ));
        twoLevelCache.evict(CacheNames.POST_DETAIL, postId);
        return toResponse(comment, userDirectory.findAuthors(List.of(authorId)));
    }

    // 评论删除后驱逐帖子详情两级缓存（切片 #13：同 create，从 @CacheEvict 迁为 twoLevelCache.evict 以覆盖 L2）
    @Transactional
    public void delete(String postId, String commentId, String authorId) {
        CommunityComment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("评论不存在"));
        if (!postId.equals(comment.getPostId())) {
            throw new ResourceNotFoundException("评论不存在");
        }
        if (!authorId.equals(comment.getAuthorId())) {
            throw new ForbiddenException("只能删除自己的评论");
        }
        if (comment.getStatus() == CommentStatus.NORMAL) {
            comment.delete(LocalDateTime.now());
        }
        twoLevelCache.evict(CacheNames.POST_DETAIL, postId);
    }

    private CommunityPost ensurePublishedPost(String postId) {
        return postRepository.findByIdAndStatus(postId, PostStatus.PUBLISHED)
                .orElseThrow(() -> new ResourceNotFoundException("帖子不存在或已删除"));
    }

    private Sort sortOf(String sort) {
        if ("hot".equalsIgnoreCase(sort)) {
            return Sort.by(Sort.Order.desc("likeCount"), Sort.Order.desc("createdAt"));
        }
        return Sort.by(Sort.Order.desc("createdAt"));
    }

    private CommentResponse toResponse(CommunityComment comment, Map<String, UserBriefDto> authors) {
        boolean deleted = comment.getStatus() == CommentStatus.DELETED;
        return new CommentResponse(
                comment.getId(), comment.getParentId(), userDirectory.toAuthor(comment.getAuthorId(), authors),
                deleted ? "该评论已删除" : comment.getContent(), deleted,
                comment.getLikeCount(), comment.getCreatedAt()
        );
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
