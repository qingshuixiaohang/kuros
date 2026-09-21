package com.kuros.kurosbackend.post.repository;

import com.kuros.kurosbackend.post.domain.PostMedia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PostMediaRepository extends JpaRepository<PostMedia, String> {

    List<PostMedia> findByPostIdOrderBySortOrderAsc(String postId);

    @Modifying(flushAutomatically = true)
    @Query("delete from PostMedia media where media.postId = :postId")
    void deleteByPostId(@Param("postId") String postId);
}
