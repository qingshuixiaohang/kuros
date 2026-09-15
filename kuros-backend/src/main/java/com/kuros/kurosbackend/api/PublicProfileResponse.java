package com.kuros.kurosbackend.api;

public record PublicProfileResponse(
        String id,
        String nickname,
        String avatarUrl,
        String bio,
        long postCount,
        long likeCount
) {
}
