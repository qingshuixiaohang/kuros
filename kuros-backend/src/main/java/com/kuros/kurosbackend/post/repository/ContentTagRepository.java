package com.kuros.kurosbackend.post.repository;

import com.kuros.kurosbackend.post.domain.ContentTag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ContentTagRepository extends JpaRepository<ContentTag, String> {

    Optional<ContentTag> findByName(String name);
}
