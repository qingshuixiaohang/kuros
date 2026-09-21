package com.kuros.kurosbackend.comment.api;

public record CreateCommentRequest(String content, String parentId) {
}
