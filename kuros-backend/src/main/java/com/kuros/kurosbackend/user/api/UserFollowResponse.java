package com.kuros.kurosbackend.user.api;

public record UserFollowResponse(
        String targetUserId,
        long followerCount,
        boolean followed
) {
}
