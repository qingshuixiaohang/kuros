package com.kuros.kurosbackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "posts")
public class CommunityPost {

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Column(name = "author_id", length = 36, nullable = false)
    private String authorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "post_type", length = 32, nullable = false)
    private PostType type;

    @Column(length = 64, nullable = false)
    private String category;

    @Column(length = 200, nullable = false)
    private String title;

    @Column(length = 500, nullable = false)
    private String excerpt;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private PostStatus status;

    @Column(name = "view_count", nullable = false)
    private long viewCount;

    @Column(name = "like_count", nullable = false)
    private long likeCount;

    @Column(name = "favorite_count", nullable = false)
    private long favoriteCount;

    @Column(name = "comment_count", nullable = false)
    private long commentCount;

    @Column(name = "published_at", nullable = false)
    private LocalDateTime publishedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "post_tags",
            joinColumns = @JoinColumn(name = "post_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id")
    )
    private Set<ContentTag> tags = new LinkedHashSet<>();

    protected CommunityPost() {
    }

    public static CommunityPost publish(String authorId, PostType type, String category, String title, String excerpt, String content, LocalDateTime now) {
        CommunityPost post = new CommunityPost();
        post.id = UUID.randomUUID().toString();
        post.authorId = authorId;
        post.type = type;
        post.category = category;
        post.title = title;
        post.excerpt = excerpt;
        post.content = content;
        post.status = PostStatus.PUBLISHED;
        post.viewCount = 0;
        post.likeCount = 0;
        post.favoriteCount = 0;
        post.commentCount = 0;
        post.publishedAt = now;
        post.createdAt = now;
        post.updatedAt = now;
        return post;
    }

    public void addTags(List<ContentTag> tags) {
        this.tags.addAll(tags);
    }

    public String getId() {
        return id;
    }

    public String getAuthorId() {
        return authorId;
    }

    public PostType getType() {
        return type;
    }

    public String getCategory() {
        return category;
    }

    public String getTitle() {
        return title;
    }

    public String getExcerpt() {
        return excerpt;
    }

    public String getContent() {
        return content;
    }

    public PostStatus getStatus() {
        return status;
    }

    public long getViewCount() {
        return viewCount;
    }

    public long getLikeCount() {
        return likeCount;
    }

    public long getFavoriteCount() {
        return favoriteCount;
    }

    public long getCommentCount() {
        return commentCount;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public Set<ContentTag> getTags() {
        return tags;
    }
}
