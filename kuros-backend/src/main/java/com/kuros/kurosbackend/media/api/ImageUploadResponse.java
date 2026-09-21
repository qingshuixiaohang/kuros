package com.kuros.kurosbackend.media.api;

public record ImageUploadResponse(
        String assetId,
        String url,
        String originalName,
        String contentType,
        long size
) {
}
