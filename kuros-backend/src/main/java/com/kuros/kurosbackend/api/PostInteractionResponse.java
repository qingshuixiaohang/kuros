package com.kuros.kurosbackend.api;

public record PostInteractionResponse(
        String postId,
        long likeCount,
        long favoriteCount,
        boolean liked,
        boolean favorited
) {
}
