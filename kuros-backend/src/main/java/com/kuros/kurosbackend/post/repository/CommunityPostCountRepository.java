package com.kuros.kurosbackend.post.repository;

import com.kuros.kurosbackend.post.domain.PostStatus;

/** Exact pagination count using only the filters present in the request. */
public interface CommunityPostCountRepository {
    long countVisiblePosts(PostStatus status, String category, String tag, String keyword);
}
