package com.kuros.kurosbackend.repository;

import com.kuros.kurosbackend.domain.CommunityUser;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommunityUserRepository extends JpaRepository<CommunityUser, String> {
}
