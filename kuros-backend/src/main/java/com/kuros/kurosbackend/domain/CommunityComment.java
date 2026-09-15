package com.kuros.kurosbackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "comments")
public class CommunityComment {

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Column(name = "post_id", length = 36, nullable = false)
    private String postId;

    @Column(name = "author_id", length = 36, nullable = false)
    private String authorId;

    @Column(name = "parent_id", length = 36)
    private String parentId;

    @Column(length = 1000, nullable = false)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private CommentStatus status;

    @Column(name = "like_count", nullable = false)
    private long likeCount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected CommunityComment() {
    }

    public CommunityComment(String id, String postId, String authorId, String parentId, String content, LocalDateTime now) {
        this.id = id;
        this.postId = postId;
        this.authorId = authorId;
        this.parentId = parentId;
        this.content = content;
        this.status = CommentStatus.NORMAL;
        this.likeCount = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getPostId() { return postId; }
    public String getAuthorId() { return authorId; }
    public String getParentId() { return parentId; }
    public String getContent() { return content; }
    public CommentStatus getStatus() { return status; }
    public long getLikeCount() { return likeCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void delete(LocalDateTime now) {
        this.status = CommentStatus.DELETED;
        this.updatedAt = now;
    }
}
