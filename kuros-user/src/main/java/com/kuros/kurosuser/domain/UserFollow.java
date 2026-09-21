package com.kuros.kurosuser.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 关注关系实体（split-07 自 kuros-backend 迁入，表名/列名逐字一致）。
 *
 * 迁移后的一致性事实：关系行只存在于本库（kuros_user），backend 的 user_follows
 * 已随 V10 迁移 DROP——跨库无 FK 成为现实，引用完整性由本服务的事务 + 双 FK
 * （指向本库 users）保证；锁外的一致性缺口由 UserFollowService 的分布式锁兜住。
 *
 * 为什么沿用 @IdClass 而不是换成 @EmbeddedId：
 * 迁移零改名（follower_id + followed_id 复合主键），UserFollowId 的
 * equals/hashCode 语义不变，existsById/deleteById 行为与迁移前逐字一致。
 */
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
