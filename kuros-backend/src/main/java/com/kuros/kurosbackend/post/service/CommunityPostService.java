package com.kuros.kurosbackend.post.service;

import com.kuros.kurosbackend.shared.api.AuthorResponse;
import com.kuros.kurosbackend.shared.api.PageMeta;
import com.kuros.kurosbackend.shared.api.PageResult;
import com.kuros.kurosbackend.post.api.PostDetailResponse;
import com.kuros.kurosbackend.post.api.PostMediaResponse;
import com.kuros.kurosbackend.post.api.PostSummaryResponse;
import com.kuros.kurosbackend.post.domain.CommunityPost;
import com.kuros.kurosbackend.media.domain.MediaAsset;
import com.kuros.kurosbackend.post.domain.PostStatus;
import com.kuros.kurosbackend.post.domain.PostMedia;
import com.kuros.kurosbackend.shared.exception.ResourceNotFoundException;
import com.kuros.kurosbackend.post.repository.CommunityPostRepository;
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
    private final PostMediaRepository postMediaRepository;
    private final MediaAssetRepository mediaAssetRepository;
    private final String publicBaseUrl;

    public CommunityPostService(
            CommunityPostRepository postRepository,
            PostMediaRepository postMediaRepository,
            MediaAssetRepository mediaAssetRepository,
            @Value("${app.storage.public-base-url:http://localhost:8080}") String publicBaseUrl
    ) {
        this.postRepository = postRepository;
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
        List<PostSummaryResponse> items = posts.getContent().stream()
                .map(this::toSummary)
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
        return toDetail(post);
    }

    public PageResult<PostSummaryResponse> findPublishedByAuthor(String authorId, int page, int pageSize) {
        int normalizedPage = Math.max(page, 1);
        int normalizedPageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(normalizedPage - 1, normalizedPageSize, Sort.by(Sort.Order.desc("publishedAt")));
        Page<CommunityPost> posts = postRepository.findByAuthorIdAndStatus(authorId, PostStatus.PUBLISHED, pageable);
        List<PostSummaryResponse> items = posts.getContent().stream()
                .map(this::toSummary)
                .toList();
        return new PageResult<>(items, new PageMeta(normalizedPage, normalizedPageSize, posts.getTotalElements(), posts.getTotalPages()));
    }

    public PageResult<PostSummaryResponse> findPublishedByIds(Page<String> ids) {
        List<CommunityPost> posts = postRepository.findAllById(ids.getContent());
        Map<String, CommunityPost> postsById = posts.stream().collect(Collectors.toMap(CommunityPost::getId, Function.identity()));
        List<PostSummaryResponse> items = ids.getContent().stream()
                .map(postsById::get)
                .filter(java.util.Objects::nonNull)
                .map(this::toSummary)
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

    private PostSummaryResponse toSummary(CommunityPost post) {
        List<PostMediaResponse> media = media(post.getId());
        return new PostSummaryResponse(
                post.getId(), post.getType(), post.getCategory(), post.getTitle(), post.getExcerpt(),
                toAuthor(post.getAuthorId()), post.getPublishedAt(), post.getViewCount(), post.getLikeCount(),
                post.getFavoriteCount(), post.getCommentCount(), tagNames(post), coverUrl(media), media
        );
    }

    private PostDetailResponse toDetail(CommunityPost post) {
        List<PostMediaResponse> media = media(post.getId());
        return new PostDetailResponse(
                post.getId(), post.getType(), post.getCategory(), post.getTitle(), post.getExcerpt(), post.getContent(),
                toAuthor(post.getAuthorId()), post.getPublishedAt(), post.getViewCount(), post.getLikeCount(),
                post.getFavoriteCount(), post.getCommentCount(), tagNames(post), coverUrl(media), media
        );
    }

    /**
     * split-07：用户表迁出本库后作者资料不再可读，统一返回占位作者。
     *
     * 为什么保留 authorId（而不是像原先作者缺失时返回 id=null）：
     * 前端关注按钮以 author.id 作为目标用户标识，id 非空时按钮可真实调用
     * 网关到 kuros-user 的关注 API——窗口期“关注”链路因此仍然可用；
     * 昵称等展示信息由 split-08 经 Feign 批量回填（kuros-user 内部 API /internal/v1/users/batch）。
     */
    private AuthorResponse toAuthor(String authorId) {
        return new AuthorResponse(authorId, "未知漂泊者", null, null);
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
