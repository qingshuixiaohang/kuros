package com.kuros.kurosbackend.api;

public record AuthUserResponse(String id, String phone, String nickname, String avatarUrl, String bio) {
}
