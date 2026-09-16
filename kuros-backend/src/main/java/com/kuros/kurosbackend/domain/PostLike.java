package com.kuros.kurosbackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "post_likes")
@IdClass(PostLikeId.class)
public class PostLike {

    @Id
    @Column(name = "user_id", length = 36, nullable = false)
    private String userId;

    @Id
    @Column(name = "post_id", length = 36, nullable = false)
    private String postId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected PostLike() {
    }

    public PostLike(String userId, String postId, LocalDateTime createdAt) {
        this.userId = userId;
        this.postId = postId;
        this.createdAt = createdAt;
    }
}
