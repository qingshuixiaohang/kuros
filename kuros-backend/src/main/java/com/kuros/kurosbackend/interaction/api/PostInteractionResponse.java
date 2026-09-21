package com.kuros.kurosbackend.interaction.api;

public record PostInteractionResponse(
        String postId,
        long likeCount,
        long favoriteCount,
        boolean liked,
        boolean favorited
) {
}
