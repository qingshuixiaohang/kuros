package com.kuros.kurosuser.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 用户实体（split-06 自 kuros-backend 迁入，表名/列名逐字一致）。
 *
 * 为什么保留包内副本而不是抽公共模块：Q5-A 决策——不建 common 模块，有纪律的复制；
 * backend 侧在 split-08（Feign 组合视图）前仍保留一份副本供内容域作者组装读取，
 * 双写共存期是迁移过程态，两侧以本库为权威（登录建号只发生在 kuros-user）。
 */
@Entity
@Table(name = "users")
public class CommunityUser {

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Column(length = 32, nullable = false, unique = true)
    private String phone;

    @Column(length = 64, nullable = false)
    private String nickname;

    @Column(name = "avatar_url", length = 512)
    private String avatarUrl;

    @Column(length = 255)
    private String bio;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private UserStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private UserRole role;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected CommunityUser() {
    }

    public CommunityUser(String id, String phone, String nickname, UserStatus status, LocalDateTime now) {
        this.id = id;
        this.phone = phone;
        this.nickname = nickname;
        this.status = status;
        this.role = UserRole.USER;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() {
        return id;
    }

    public String getPhone() {
        return phone;
    }

    public String getNickname() {
        return nickname;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public String getBio() {
        return bio;
    }

    public UserStatus getStatus() {
        return status;
    }

    public UserRole getRole() {
        return role;
    }

    /**
     * 设置角色（冗余快捷字段）。
     * RBAC 表（sys_user_role）是权威来源，这个字段只是保持现有 API 响应格式不变。
     * 登录时由 AuthService.syncRoleField() 调用。
     */
    public void setRole(UserRole role) {
        this.role = role;
    }
}
