package com.kuros.kurosbackend.post.api;

import com.kuros.kurosbackend.shared.api.AuthorResponse;

import com.kuros.kurosbackend.post.domain.PostType;

import java.time.LocalDateTime;
import java.util.List;

public record PostSummaryResponse(
        String id,
        PostType type,
        String category,
        String title,
        String excerpt,
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
