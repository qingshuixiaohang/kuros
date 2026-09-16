package com.kuros.kurosbackend.service;

import com.kuros.kurosbackend.api.CreatePostRequest;
import com.kuros.kurosbackend.api.PostDetailResponse;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;

@Service
public class PostPublishingService {

    private final CommunityPostRepository postRepository;
    private final CommunityUserRepository userRepository;
    private final ContentTagRepository tagRepository;
    private final CommunityPostService postService;

    public PostPublishingService(CommunityPostRepository postRepository, CommunityUserRepository userRepository, ContentTagRepository tagRepository, CommunityPostService postService) {
        this.postRepository = postRepository;
        this.userRepository = userRepository;
        this.tagRepository = tagRepository;
        this.postService = postService;
    }

    @Transactional
    public PostDetailResponse publish(CreatePostRequest request, String authorId) {
        userRepository.findById(authorId).orElseThrow(() -> new ResourceNotFoundException("用户不存在"));
        ValidatedPost input = validate(request);
        List<String> tagNames = normalizeTags(request.tags());
        CommunityPost post = CommunityPost.publish(authorId, input.type(), input.category(), input.title(), input.excerpt(), input.content(), LocalDateTime.now());
        post.addTags(tagNames.stream().map(name -> tagRepository.findByName(name).orElseGet(() -> tagRepository.save(new ContentTag(name)))).toList());
        CommunityPost saved = postRepository.save(post);
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
}
