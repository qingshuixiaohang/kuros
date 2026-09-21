package com.kuros.kurosbackend.service;

import com.kuros.kurosbackend.shared.api.AuthorResponse;
import com.kuros.kurosbackend.api.CommentResponse;
import com.kuros.kurosbackend.api.CreateCommentRequest;
import com.kuros.kurosbackend.shared.api.PageMeta;
import com.kuros.kurosbackend.shared.api.PageResult;
import com.kuros.kurosbackend.domain.CommentStatus;
import com.kuros.kurosbackend.domain.CommunityComment;
import com.kuros.kurosbackend.domain.CommunityPost;
import com.kuros.kurosbackend.domain.CommunityUser;
import com.kuros.kurosbackend.domain.PostStatus;
import com.kuros.kurosbackend.shared.exception.AuthRequestException;
import com.kuros.kurosbackend.shared.exception.ForbiddenException;
import com.kuros.kurosbackend.shared.exception.ResourceNotFoundException;
import com.kuros.kurosbackend.repository.CommunityCommentRepository;
import com.kuros.kurosbackend.repository.CommunityPostRepository;
import com.kuros.kurosbackend.repository.CommunityUserRepository;
import org.springframework.cache.annotation.CacheEvict;
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
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CommunityCommentService {

    private static final int MAX_PAGE_SIZE = 50;

    private final CommunityCommentRepository commentRepository;
    private final CommunityPostRepository postRepository;
    private final CommunityUserRepository userRepository;

    public CommunityCommentService(
            CommunityCommentRepository commentRepository,
            CommunityPostRepository postRepository,
            CommunityUserRepository userRepository
    ) {
        this.commentRepository = commentRepository;
        this.postRepository = postRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public PageResult<CommentResponse> findByPost(String postId, int page, int pageSize, String sort) {
        ensurePublishedPost(postId);
        int normalizedPage = Math.max(page, 1);
        int normalizedPageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(normalizedPage - 1, normalizedPageSize, sortOf(sort));
        Page<CommunityComment> comments = commentRepository.findByPostId(postId, pageable);
        Map<String, CommunityUser> authors = authorsById(comments.getContent());
        List<CommentResponse> items = comments.getContent().stream()
                .map(comment -> toResponse(comment, authors.get(comment.getAuthorId())))
                .toList();
        return new PageResult<>(items, new PageMeta(normalizedPage, normalizedPageSize, comments.getTotalElements(), comments.getTotalPages()));
    }

    // 评论创建后驱逐帖子详情缓存，确保下次查询获取最新评论数
    @CacheEvict(cacheNames = "postDetail", key = "#postId")
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
        CommunityUser author = userRepository.findById(authorId)
                .orElseThrow(() -> new ForbiddenException("当前用户不存在"));
        LocalDateTime now = LocalDateTime.now();
        CommunityComment comment = commentRepository.save(new CommunityComment(
                UUID.randomUUID().toString(), postId, authorId, parentId, content, now
        ));
        return toResponse(comment, author);
    }

    // 评论删除后驱逐帖子详情缓存
    @CacheEvict(cacheNames = "postDetail", key = "#postId")
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

    private Map<String, CommunityUser> authorsById(List<CommunityComment> comments) {
        return userRepository.findAllById(comments.stream().map(CommunityComment::getAuthorId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(CommunityUser::getId, Function.identity()));
    }

    private CommentResponse toResponse(CommunityComment comment, CommunityUser author) {
        boolean deleted = comment.getStatus() == CommentStatus.DELETED;
        return new CommentResponse(
                comment.getId(), comment.getParentId(), toAuthor(author),
                deleted ? "该评论已删除" : comment.getContent(), deleted,
                comment.getLikeCount(), comment.getCreatedAt()
        );
    }

    private AuthorResponse toAuthor(CommunityUser author) {
        if (author == null) {
            return new AuthorResponse(null, "未知漂泊者", null, null);
        }
        return new AuthorResponse(author.getId(), author.getNickname(), author.getAvatarUrl(), author.getBio());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
