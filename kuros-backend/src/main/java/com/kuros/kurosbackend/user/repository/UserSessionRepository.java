package com.kuros.kurosbackend.user.repository;

import com.kuros.kurosbackend.user.domain.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserSessionRepository extends JpaRepository<UserSession, String> {

    Optional<UserSession> findByTokenHash(String tokenHash);

    void deleteByTokenHash(String tokenHash);
}
