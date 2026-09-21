package com.kuros.kurosbackend.user.api;

import com.kuros.kurosbackend.user.domain.UserRole;

public record AuthUserResponse(String id, String phone, String nickname, String avatarUrl, String bio, UserRole role) {
}
