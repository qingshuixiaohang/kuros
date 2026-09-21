package com.kuros.kurosbackend.post.service;

import com.kuros.kurosbackend.shared.api.AuthorResponse;
import com.kuros.kurosbackend.shared.api.PageMeta;
import com.kuros.kurosbackend.shared.api.PageResult;
import com.kuros.kurosbackend.post.api.PostDetailResponse;
import com.kuros.kurosbackend.post.api.PostMediaResponse;
import com.kuros.kurosbackend.post.api.PostSummaryResponse;
import com.kuros.kurosbackend.post.domain.CommunityPost;
import com.kuros.kurosbackend.user.domain.CommunityUser;
import com.kuros.kurosbackend.media.domain.MediaAsset;
import com.kuros.kurosbackend.post.domain.PostStatus;
import com.kuros.kurosbackend.post.domain.PostMedia;
import com.kuros.kurosbackend.shared.exception.ResourceNotFoundException;
import com.kuros.kurosbackend.post.repository.CommunityPostRepository;
import com.kuros.kurosbackend.user.repository.CommunityUserRepository;
import com.kuros.kurosbackend.media.repository.MediaAssetRepository;
import com.kuros.kurosbackend.post.repository.PostMediaRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
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
    private final PostMediaRepository postMediaRepository;
    private final MediaAssetRepository mediaAssetRepository;
    private final String publicBaseUrl;

    public CommunityPostService(
            CommunityPostRepository postRepository,
            CommunityUserRepository userRepository,
            PostMediaRepository postMediaRepository,
            MediaAssetRepository mediaAssetRepository,
            @Value("${app.storage.public-base-url:http://localhost:8080}") String publicBaseUrl
    ) {
        this.postRepository = postRepository;
        this.userRepository = userRepository;
        this.postMediaRepository = postMediaRepository;
        this.mediaAssetRepository = mediaAssetRepository;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/$", "");
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

    // @Cacheable：首次查询穿透 DB 后写入缓存，后续相同 postId 直接返回缓存值。
    // 为什么只缓存这一个方法而不是列表查询？
    // 因为帖子详情是“读多写少”的典型场景，而分页列表参数组合多、命中率低，缓存收益小。
    @Cacheable(cacheNames = "postDetail", key = "#id")
    public PostDetailResponse findPublishedById(String id) {
        CommunityPost post = postRepository.findByIdAndStatus(id, PostStatus.PUBLISHED)
                .orElseThrow(() -> new ResourceNotFoundException("帖子不存在或已删除"));
        CommunityUser author = userRepository.findById(post.getAuthorId()).orElse(null);
        return toDetail(post, author);
    }

    public PageResult<PostSummaryResponse> findPublishedByAuthor(String authorId, int page, int pageSize) {
        int normalizedPage = Math.max(page, 1);
        int normalizedPageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(normalizedPage - 1, normalizedPageSize, Sort.by(Sort.Order.desc("publishedAt")));
        Page<CommunityPost> posts = postRepository.findByAuthorIdAndStatus(authorId, PostStatus.PUBLISHED, pageable);
        Map<String, CommunityUser> authors = authorsById(posts.getContent());
        List<PostSummaryResponse> items = posts.getContent().stream()
                .map(post -> toSummary(post, authors.get(post.getAuthorId())))
                .toList();
        return new PageResult<>(items, new PageMeta(normalizedPage, normalizedPageSize, posts.getTotalElements(), posts.getTotalPages()));
    }

    public PageResult<PostSummaryResponse> findPublishedByIds(Page<String> ids) {
        List<CommunityPost> posts = postRepository.findAllById(ids.getContent());
        Map<String, CommunityPost> postsById = posts.stream().collect(Collectors.toMap(CommunityPost::getId, Function.identity()));
        Map<String, CommunityUser> authors = authorsById(posts);
        List<PostSummaryResponse> items = ids.getContent().stream()
                .map(postsById::get)
                .filter(java.util.Objects::nonNull)
                .map(post -> toSummary(post, authors.get(post.getAuthorId())))
                .toList();
        return new PageResult<>(items, new PageMeta(ids.getNumber() + 1, ids.getSize(), ids.getTotalElements(), ids.getTotalPages()));
    }

    public long publishedPostCount(String authorId) {
        return postRepository.countByAuthorIdAndStatus(authorId, PostStatus.PUBLISHED);
    }

    public long publishedPostLikeCount(String authorId) {
        return postRepository.sumLikeCountByAuthorIdAndStatus(authorId, PostStatus.PUBLISHED);
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
        List<PostMediaResponse> media = media(post.getId());
        return new PostSummaryResponse(
                post.getId(), post.getType(), post.getCategory(), post.getTitle(), post.getExcerpt(),
                toAuthor(author), post.getPublishedAt(), post.getViewCount(), post.getLikeCount(),
                post.getFavoriteCount(), post.getCommentCount(), tagNames(post), coverUrl(media), media
        );
    }

    private PostDetailResponse toDetail(CommunityPost post, CommunityUser author) {
        List<PostMediaResponse> media = media(post.getId());
        return new PostDetailResponse(
                post.getId(), post.getType(), post.getCategory(), post.getTitle(), post.getExcerpt(), post.getContent(),
                toAuthor(author), post.getPublishedAt(), post.getViewCount(), post.getLikeCount(),
                post.getFavoriteCount(), post.getCommentCount(), tagNames(post), coverUrl(media), media
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

    private String coverUrl(List<PostMediaResponse> media) {
        return media.stream().findFirst().map(PostMediaResponse::url).orElse(null);
    }

    private List<PostMediaResponse> media(String postId) {
        List<PostMedia> associations = postMediaRepository.findByPostIdOrderBySortOrderAsc(postId);
        Map<String, MediaAsset> assets = mediaAssetRepository.findAllById(
                        associations.stream().map(PostMedia::getAssetId).toList()
                ).stream()
                .collect(Collectors.toMap(MediaAsset::getId, Function.identity()));
        return associations.stream()
                .map(association -> assets.get(association.getAssetId()))
                .filter(java.util.Objects::nonNull)
                .map(asset -> new PostMediaResponse(
                        asset.getId(), publicBaseUrl + "/media/" + asset.getStorageKey(),
                        associations.stream().filter(item -> item.getAssetId().equals(asset.getId())).findFirst().orElseThrow().getSortOrder(),
                        associations.stream().filter(item -> item.getAssetId().equals(asset.getId())).findFirst().orElseThrow().getSortOrder() == 0
                ))
                .toList();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
