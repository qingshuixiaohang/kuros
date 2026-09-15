package com.kuros.kurosbackend.repository;

import com.kuros.kurosbackend.domain.PostLike;
import com.kuros.kurosbackend.domain.PostLikeId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostLikeRepository extends JpaRepository<PostLike, PostLikeId> {
}
