package com.kuros.kurosbackend.service;

import com.kuros.kurosbackend.api.AuthorResponse;
import com.kuros.kurosbackend.api.PageMeta;
import com.kuros.kurosbackend.api.PageResult;
import com.kuros.kurosbackend.api.PostDetailResponse;
import com.kuros.kurosbackend.api.PostSummaryResponse;
import com.kuros.kurosbackend.domain.CommunityPost;
import com.kuros.kurosbackend.domain.CommunityUser;
import com.kuros.kurosbackend.domain.PostStatus;
import com.kuros.kurosbackend.exception.ResourceNotFoundException;
import com.kuros.kurosbackend.repository.CommunityPostRepository;
import com.kuros.kurosbackend.repository.CommunityUserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class CommunityPostService {

    private static final int MAX_PAGE_SIZE = 50;

    private final CommunityPostRepository postRepository;
    private final CommunityUserRepository userRepository;

    public CommunityPostService(CommunityPostRepository postRepository, CommunityUserRepository userRepository) {
        this.postRepository = postRepository;
        this.userRepository = userRepository;
    }

    public PageResult<PostSummaryResponse> findPublished(
            int page,
            int pageSize,
            String sort,
            String category,
            String tag,
            String keyword
    ) {
        int normalizedPage = Math.max(page, 1);
        int normalizedPageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(normalizedPage - 1, normalizedPageSize, sortOf(sort));
        Page<CommunityPost> posts = postRepository.findVisiblePosts(
                PostStatus.PUBLISHED,
                blankToNull(category),
                blankToNull(tag),
                blankToNull(keyword),
                pageable
        );
        Map<String, CommunityUser> authors = authorsById(posts.getContent());
        List<PostSummaryResponse> items = posts.getContent().stream()
                .map(post -> toSummary(post, authors.get(post.getAuthorId())))
                .toList();
        PageMeta meta = new PageMeta(normalizedPage, normalizedPageSize, posts.getTotalElements(), posts.getTotalPages());
        return new PageResult<>(items, meta);
    }

    public PostDetailResponse findPublishedById(String id) {
        CommunityPost post = postRepository.findByIdAndStatus(id, PostStatus.PUBLISHED)
                .orElseThrow(() -> new ResourceNotFoundException("帖子不存在或已删除"));
        CommunityUser author = userRepository.findById(post.getAuthorId()).orElse(null);
        return toDetail(post, author);
    }

    private Sort sortOf(String sort) {
        if ("hot".equalsIgnoreCase(sort)) {
            return Sort.by(Sort.Order.desc("likeCount"), Sort.Order.desc("commentCount"), Sort.Order.desc("publishedAt"));
        }
        return Sort.by(Sort.Order.desc("publishedAt"));
    }

    private Map<String, CommunityUser> authorsById(List<CommunityPost> posts) {
        return userRepository.findAllById(posts.stream().map(CommunityPost::getAuthorId).collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(CommunityUser::getId, Function.identity()));
    }

    private PostSummaryResponse toSummary(CommunityPost post, CommunityUser author) {
        return new PostSummaryResponse(
                post.getId(), post.getType(), post.getCategory(), post.getTitle(), post.getExcerpt(),
                toAuthor(author), post.getPublishedAt(), post.getViewCount(), post.getLikeCount(),
                post.getFavoriteCount(), post.getCommentCount(), tagNames(post)
        );
    }

    private PostDetailResponse toDetail(CommunityPost post, CommunityUser author) {
        return new PostDetailResponse(
                post.getId(), post.getType(), post.getCategory(), post.getTitle(), post.getExcerpt(), post.getContent(),
                toAuthor(author), post.getPublishedAt(), post.getViewCount(), post.getLikeCount(),
                post.getFavoriteCount(), post.getCommentCount(), tagNames(post)
        );
    }

    private AuthorResponse toAuthor(CommunityUser author) {
        if (author == null) {
            return new AuthorResponse(null, "未知漂泊者", null, null);
        }
        return new AuthorResponse(author.getId(), author.getNickname(), author.getAvatarUrl(), author.getBio());
    }

    private List<String> tagNames(CommunityPost post) {
        return post.getTags().stream().map(tag -> tag.getName()).sorted().toList();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
