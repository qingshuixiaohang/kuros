package com.kuros.kurosbackend.domain;

import java.io.Serializable;
import java.util.Objects;

public class PostLikeId implements Serializable {

    private String userId;
    private String postId;

    protected PostLikeId() {
    }

    public PostLikeId(String userId, String postId) {
        this.userId = userId;
        this.postId = postId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof PostLikeId that)) return false;
        return Objects.equals(userId, that.userId) && Objects.equals(postId, that.postId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, postId);
    }
}
