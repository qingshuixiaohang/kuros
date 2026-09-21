package com.kuros.kurosbackend.user.repository;

import com.kuros.kurosbackend.user.domain.UserFollow;
import com.kuros.kurosbackend.user.domain.UserFollowId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserFollowRepository extends JpaRepository<UserFollow, UserFollowId> {

    long countByFollowedId(String followedId);

    Page<UserFollow> findByFollowerIdOrderByCreatedAtDesc(String followerId, Pageable pageable);

    Page<UserFollow> findByFollowedIdOrderByCreatedAtDesc(String followedId, Pageable pageable);
}
