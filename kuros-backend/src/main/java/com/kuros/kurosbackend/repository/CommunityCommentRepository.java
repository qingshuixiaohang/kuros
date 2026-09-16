package com.kuros.kurosbackend.repository;

import com.kuros.kurosbackend.domain.CommunityComment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommunityCommentRepository extends JpaRepository<CommunityComment, String> {

    Page<CommunityComment> findByPostId(String postId, Pageable pageable);

    Page<CommunityComment> findByAuthorId(String authorId, Pageable pageable);
}
