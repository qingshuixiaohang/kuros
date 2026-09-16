package com.kuros.kurosbackend.repository;

import com.kuros.kurosbackend.domain.UserFollow;
import com.kuros.kurosbackend.domain.UserFollowId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserFollowRepository extends JpaRepository<UserFollow, UserFollowId> {

    long countByFollowedId(String followedId);

    Page<UserFollow> findByFollowerIdOrderByCreatedAtDesc(String followerId, Pageable pageable);

    Page<UserFollow> findByFollowedIdOrderByCreatedAtDesc(String followedId, Pageable pageable);
}
