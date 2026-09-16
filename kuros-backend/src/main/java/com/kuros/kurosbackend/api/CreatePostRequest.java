package com.kuros.kurosbackend.api;

import java.util.List;

public record CreatePostRequest(
        String type,
        String category,
        String title,
        String excerpt,
        String content,
        List<String> tags
) {
}
