package com.kuros.kurosuser.domain;

import java.io.Serializable;
import java.util.Objects;

/**
 * 关注关系复合主键（split-07 自 kuros-backend 迁入，逐字一致）。
 *
 * 必须实现 Serializable：JPA 复合主键的规范要求（也随 @IdClass 用于
 * existsById/deleteById 的实体管理器查找）。
 */
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
