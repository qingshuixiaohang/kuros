package com.kuros.kurosbackend.api;

public record UserFollowResponse(
        String targetUserId,
        long followerCount,
        boolean followed
) {
}
