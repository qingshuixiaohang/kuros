package com.kuros.kurosbackend.user.api;

import java.time.LocalDateTime;

public record ProfileCommentResponse(
        String id,
        String postId,
        String postTitle,
        String parentId,
        String content,
        boolean deleted,
        LocalDateTime createdAt
) {
}
