package com.kuros.kurosbackend.domain;

import java.io.Serializable;
import java.util.Objects;

public class UserFollowId implements Serializable {

    private String followerId;
    private String followedId;

    protected UserFollowId() {
    }

    public UserFollowId(String followerId, String followedId) {
        this.followerId = followerId;
        this.followedId = followedId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof UserFollowId that)) return false;
        return Objects.equals(followerId, that.followerId) && Objects.equals(followedId, that.followedId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(followerId, followedId);
    }
}
