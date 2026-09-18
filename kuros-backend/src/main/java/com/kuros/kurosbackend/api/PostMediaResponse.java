package com.kuros.kurosbackend.api;

public record PostMediaResponse(
        String id,
        String url,
        int sortOrder,
        boolean isCover
) {
}
