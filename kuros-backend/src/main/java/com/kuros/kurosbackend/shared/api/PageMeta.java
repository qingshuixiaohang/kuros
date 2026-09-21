package com.kuros.kurosbackend.shared.api;


public record PageMeta(int page, int pageSize, long totalItems, int totalPages) {
}
