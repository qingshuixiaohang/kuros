package com.kuros.kurosbackend.interaction.repository;

import com.kuros.kurosbackend.interaction.domain.PostFavorite;
import com.kuros.kurosbackend.interaction.domain.PostFavoriteId;
import com.kuros.kurosbackend.post.domain.PostStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostFavoriteRepository extends JpaRepository<PostFavorite, PostFavoriteId> {

    @Query(value = "select f.postId from PostFavorite f, CommunityPost p where f.postId = p.id and f.userId = :userId and p.status = :status order by f.createdAt desc",
            countQuery = "select count(f) from PostFavorite f, CommunityPost p where f.postId = p.id and f.userId = :userId and p.status = :status")
    Page<String> findVisiblePostIds(@Param("userId") String userId, @Param("status") PostStatus status, Pageable pageable);

    /** 统计某帖的真实收藏数（审计 M5 / B3：收藏计数对账的真相来源）。 */
    @Query("select count(f) from PostFavorite f where f.postId = :postId")
    long countByPostId(@Param("postId") String postId);
}
