package com.kuros.kurosbackend.api;

import com.kuros.kurosbackend.domain.UserRole;

public record AuthUserResponse(String id, String phone, String nickname, String avatarUrl, String bio, UserRole role) {
}
