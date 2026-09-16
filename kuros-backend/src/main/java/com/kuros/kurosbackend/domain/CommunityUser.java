package com.kuros.kurosbackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

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
}
