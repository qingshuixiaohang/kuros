package com.kuros.kurosbackend.post.api;

public record PostMediaResponse(
        String id,
        String url,
        int sortOrder,
        boolean isCover
) {
}
