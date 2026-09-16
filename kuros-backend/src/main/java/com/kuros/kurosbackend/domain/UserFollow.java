package com.kuros.kurosbackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_follows")
@IdClass(UserFollowId.class)
public class UserFollow {

    @Id
    @Column(name = "follower_id", length = 36, nullable = false)
    private String followerId;

    @Id
    @Column(name = "followed_id", length = 36, nullable = false)
    private String followedId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected UserFollow() {
    }

    public UserFollow(String followerId, String followedId, LocalDateTime createdAt) {
        this.followerId = followerId;
        this.followedId = followedId;
        this.createdAt = createdAt;
    }

    public String getFollowerId() { return followerId; }
    public String getFollowedId() { return followedId; }
}
