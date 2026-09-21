package com.kuros.kurosbackend.api;

import com.kuros.kurosbackend.shared.api.AuthorResponse;

import com.kuros.kurosbackend.domain.PostType;

import java.time.LocalDateTime;
import java.util.List;

public record PostDetailResponse(
        String id,
        PostType type,
        String category,
        String title,
        String excerpt,
        String content,
        AuthorResponse author,
        LocalDateTime publishedAt,
        long viewCount,
        long likeCount,
        long favoriteCount,
        long commentCount,
        List<String> tags,
        String coverImageUrl,
        List<PostMediaResponse> media
) {
}
