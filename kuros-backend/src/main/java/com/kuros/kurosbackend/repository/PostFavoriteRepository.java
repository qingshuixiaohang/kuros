package com.kuros.kurosbackend.repository;

import com.kuros.kurosbackend.domain.PostFavorite;
import com.kuros.kurosbackend.domain.PostFavoriteId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostFavoriteRepository extends JpaRepository<PostFavorite, PostFavoriteId> {
}
