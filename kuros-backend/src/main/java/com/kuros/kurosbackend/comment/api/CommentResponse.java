package com.kuros.kurosbackend.comment.api;

import com.kuros.kurosbackend.shared.api.AuthorResponse;

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
