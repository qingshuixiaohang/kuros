package com.kuros.kurosuser.api;

import com.kuros.kurosuser.domain.UserRole;

public record AuthUserResponse(String id, String phone, String nickname, String avatarUrl, String bio, UserRole role) {
}
