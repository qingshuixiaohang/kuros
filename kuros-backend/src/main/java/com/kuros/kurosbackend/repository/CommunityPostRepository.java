package com.kuros.kurosbackend.repository;

import com.kuros.kurosbackend.domain.CommunityPost;
import com.kuros.kurosbackend.domain.PostStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CommunityPostRepository extends JpaRepository<CommunityPost, String> {

    @Query("""
            select distinct p from CommunityPost p
            left join p.tags tag
            where p.status = :status
              and (:category is null or p.category = :category)
              and (:tag is null or tag.name = :tag)
              and (:keyword is null or lower(p.title) like lower(concat('%', :keyword, '%'))
                   or lower(p.excerpt) like lower(concat('%', :keyword, '%')))
            """)
    Page<CommunityPost> findVisiblePosts(
            @Param("status") PostStatus status,
            @Param("category") String category,
            @Param("tag") String tag,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    Optional<CommunityPost> findByIdAndStatus(String id, PostStatus status);
}
