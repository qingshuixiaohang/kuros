package com.kuros.kurosbackend.api;

public record PageMeta(int page, int pageSize, long totalItems, int totalPages) {
}
