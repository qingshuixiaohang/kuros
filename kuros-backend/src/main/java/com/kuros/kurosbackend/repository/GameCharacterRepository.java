package com.kuros.kurosbackend.repository;

import com.kuros.kurosbackend.domain.GameCharacter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface GameCharacterRepository extends JpaRepository<GameCharacter, String> {

    @Query("""
            select character from GameCharacter character
            where character.enabled = true
              and (:role is null or character.role = :role)
              and (:keyword is null or lower(character.name) like lower(concat('%', :keyword, '%'))
                   or lower(character.attribute) like lower(concat('%', :keyword, '%'))
                   or lower(character.weaponType) like lower(concat('%', :keyword, '%'))
                   or lower(character.description) like lower(concat('%', :keyword, '%')))
            order by character.sortOrder asc, character.name asc
            """)
    Page<GameCharacter> findVisible(
            @Param("role") String role,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    Optional<GameCharacter> findBySlugAndEnabledTrue(String slug);
}
