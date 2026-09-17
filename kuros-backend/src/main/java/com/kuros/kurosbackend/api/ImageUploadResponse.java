package com.kuros.kurosbackend.api;

public record ImageUploadResponse(
        String assetId,
        String url,
        String originalName,
        String contentType,
        long size
) {
}
