package com.kuros.kurosbackend.api;

public record CreateCommentRequest(String content, String parentId) {
}
