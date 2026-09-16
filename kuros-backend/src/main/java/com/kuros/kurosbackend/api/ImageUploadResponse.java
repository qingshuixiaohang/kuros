package com.kuros.kurosbackend.api;

public record ImageUploadResponse(
        String url,
        String originalName,
        String contentType,
        long size
) {
}
