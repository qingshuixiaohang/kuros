package com.kuros.kurosbackend.api;

import java.time.LocalDateTime;

public record CommentResponse(
        String id,
        String parentId,
        AuthorResponse author,
        String content,
        boolean deleted,
        long likeCount,
        LocalDateTime createdAt
) {
}
