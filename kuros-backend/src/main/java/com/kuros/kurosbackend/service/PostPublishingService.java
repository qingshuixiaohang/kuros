package com.kuros.kurosbackend.service;

import com.kuros.kurosbackend.api.CreatePostRequest;
import com.kuros.kurosbackend.api.PostDetailResponse;
import com.kuros.kurosbackend.domain.MediaAsset;
import com.kuros.kurosbackend.domain.MediaAssetStatus;
import com.kuros.kurosbackend.domain.PostMedia;
import com.kuros.kurosbackend.domain.CommunityPost;
import com.kuros.kurosbackend.domain.ContentTag;
import com.kuros.kurosbackend.domain.PostType;
import com.kuros.kurosbackend.domain.PostStatus;
import com.kuros.kurosbackend.exception.AuthRequestException;
import com.kuros.kurosbackend.exception.ForbiddenException;
import com.kuros.kurosbackend.exception.ResourceNotFoundException;
import com.kuros.kurosbackend.repository.CommunityPostRepository;
import com.kuros.kurosbackend.repository.CommunityUserRepository;
import com.kuros.kurosbackend.repository.ContentTagRepository;
import com.kuros.kurosbackend.repository.MediaAssetRepository;
import com.kuros.kurosbackend.repository.PostMediaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PostPublishingService {

    private final CommunityPostRepository postRepository;
    private final CommunityUserRepository userRepository;
    private final ContentTagRepository tagRepository;
    private final CommunityPostService postService;
    private final MediaAssetRepository mediaAssetRepository;
    private final PostMediaRepository postMediaRepository;

    public PostPublishingService(
            CommunityPostRepository postRepository,
            CommunityUserRepository userRepository,
            ContentTagRepository tagRepository,
            CommunityPostService postService,
            MediaAssetRepository mediaAssetRepository,
            PostMediaRepository postMediaRepository
    ) {
        this.postRepository = postRepository;
        this.userRepository = userRepository;
        this.tagRepository = tagRepository;
        this.postService = postService;
        this.mediaAssetRepository = mediaAssetRepository;
        this.postMediaRepository = postMediaRepository;
    }

    @Transactional
    public PostDetailResponse publish(CreatePostRequest request, String authorId) {
        userRepository.findById(authorId).orElseThrow(() -> new ResourceNotFoundException("用户不存在"));
        ValidatedPost input = validate(request);
        List<String> tagNames = normalizeTags(request.tags());
        CommunityPost post = CommunityPost.publish(authorId, input.type(), input.category(), input.title(), input.excerpt(), input.content(), LocalDateTime.now());
        post.addTags(tagNames.stream().map(name -> tagRepository.findByName(name).orElseGet(() -> tagRepository.save(new ContentTag(name)))).toList());
        CommunityPost saved = postRepository.save(post);
        replaceMedia(saved.getId(), List.of(), mediaAssets(request.mediaAssetIds(), authorId, null));
        return postService.findPublishedById(saved.getId());
    }

    @Transactional
    public PostDetailResponse update(String postId, String authorId, CreatePostRequest request) {
        CommunityPost post = ownedPost(postId, authorId);
        ValidatedPost input = validate(request);
        List<String> tagNames = normalizeTags(request.tags());
        post.update(input.type(), input.category(), input.title(), input.excerpt(), input.content(), LocalDateTime.now());
        post.replaceTags(tagNames.stream().map(name -> tagRepository.findByName(name).orElseGet(() -> tagRepository.save(new ContentTag(name)))).toList());
        postRepository.save(post);
        replaceMedia(post.getId(), postMediaRepository.findByPostIdOrderBySortOrderAsc(post.getId()), mediaAssets(request.mediaAssetIds(), authorId, post.getId()));
        return postService.findPublishedById(post.getId());
    }

    @Transactional
    public void delete(String postId, String authorId) {
        CommunityPost post = postRepository.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("帖子不存在或已删除"));
        if (!authorId.equals(post.getAuthorId())) {
            throw new ForbiddenException("只能管理自己的帖子");
        }
        if (post.getStatus() != PostStatus.DELETED) {
            post.delete(LocalDateTime.now());
        }
    }

    private CommunityPost ownedPost(String postId, String authorId) {
        CommunityPost post = postRepository.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("帖子不存在或已删除"));
        if (!authorId.equals(post.getAuthorId())) {
            throw new ForbiddenException("只能管理自己的帖子");
        }
        if (post.getStatus() == PostStatus.DELETED) {
            throw new ResourceNotFoundException("帖子不存在或已删除");
        }
        return post;
    }

    private ValidatedPost validate(CreatePostRequest request) {
        if (request == null) throw new AuthRequestException("POST_INVALID", "帖子内容不能为空");
        PostType type = parseType(request.type());
        String category = required(request.category(), "POST_CATEGORY_REQUIRED", "请选择内容分类", 64);
        String title = required(request.title(), "POST_TITLE_REQUIRED", "请输入帖子标题", 200);
        String content = required(request.content(), "POST_CONTENT_REQUIRED", "请输入帖子正文", 50000);
        String excerpt = request.excerpt() == null || request.excerpt().isBlank() ? summarize(content) : required(request.excerpt(), "POST_EXCERPT_INVALID", "帖子摘要不能为空", 500);
        return new ValidatedPost(type, category, title, excerpt, content);
    }

    private record ValidatedPost(PostType type, String category, String title, String excerpt, String content) {}

    private PostType parseType(String value) {
        try { return PostType.valueOf(value == null ? "" : value.trim().toUpperCase()); }
        catch (IllegalArgumentException exception) { throw new AuthRequestException("POST_TYPE_INVALID", "内容类型不合法"); }
    }

    private String required(String value, String code, String message, int maxLength) {
        if (value == null || value.isBlank()) throw new AuthRequestException(code, message);
        String normalized = value.trim();
        if (normalized.length() > maxLength) throw new AuthRequestException(code, message + "，长度不能超过 " + maxLength + " 个字符");
        return normalized;
    }

    private String summarize(String content) {
        String plain = content.replaceAll("[#>*_`~-]", " ").replaceAll("\\s+", " ").trim();
        return plain.length() <= 500 ? plain : plain.substring(0, 500);
    }

    private List<String> normalizeTags(List<String> tags) {
        if (tags == null) return List.of();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String tag : tags) {
            if (tag == null || tag.isBlank()) continue;
            String value = tag.trim();
            if (value.length() > 64) throw new AuthRequestException("POST_TAG_INVALID", "标签长度不能超过 64 个字符");
            normalized.add(value);
        }
        if (normalized.size() > 10) throw new AuthRequestException("POST_TAG_INVALID", "最多添加 10 个标签");
        return List.copyOf(normalized);
    }

    private List<MediaAsset> mediaAssets(List<String> ids, String ownerId, String postId) {
        List<String> normalized = ids == null ? List.of() : ids;
        if (normalized.size() > 9) {
            throw new AuthRequestException("MEDIA_TOO_MANY", "每个帖子最多上传 9 张图片");
        }
        if (new LinkedHashSet<>(normalized).size() != normalized.size()) {
            throw new AuthRequestException("MEDIA_DUPLICATE", "图片不能重复添加");
        }
        Map<String, MediaAsset> assets = mediaAssetRepository.findAllById(normalized).stream()
                .collect(Collectors.toMap(MediaAsset::getId, asset -> asset));
        Set<String> currentIds = postId == null ? Set.of() : postMediaRepository.findByPostIdOrderBySortOrderAsc(postId).stream()
                .map(PostMedia::getAssetId).collect(Collectors.toSet());
        return normalized.stream().map(id -> {
            MediaAsset asset = assets.get(id);
            if (asset == null || asset.getStatus() == MediaAssetStatus.DELETED) {
                throw new AuthRequestException("MEDIA_NOT_FOUND", "图片资源不存在或已失效");
            }
            if (!ownerId.equals(asset.getOwnerId())) {
                throw new ForbiddenException("不能使用其他用户上传的图片");
            }
            if (asset.getStatus() == MediaAssetStatus.ATTACHED && !currentIds.contains(id)) {
                throw new AuthRequestException("MEDIA_ALREADY_ATTACHED", "图片已经关联其他帖子");
            }
            return asset;
        }).toList();
    }

    private void replaceMedia(String postId, List<PostMedia> previous, List<MediaAsset> next) {
        Set<String> nextIds = next.stream().map(MediaAsset::getId).collect(Collectors.toSet());
        Map<String, MediaAsset> previousAssets = mediaAssetRepository.findAllById(previous.stream().map(PostMedia::getAssetId).toList()).stream()
                .collect(Collectors.toMap(MediaAsset::getId, asset -> asset));
        previousAssets.values().stream().filter(asset -> !nextIds.contains(asset.getId())).forEach(MediaAsset::markDeleted);
        LocalDateTime now = LocalDateTime.now();
        next.forEach(asset -> asset.attach(now));
        mediaAssetRepository.saveAll(previousAssets.values());
        mediaAssetRepository.saveAll(next);
        postMediaRepository.deleteByPostId(postId);
        postMediaRepository.saveAll(java.util.stream.IntStream.range(0, next.size())
                .mapToObj(index -> PostMedia.create(postId, next.get(index).getId(), index, now))
                .toList());
    }
}
