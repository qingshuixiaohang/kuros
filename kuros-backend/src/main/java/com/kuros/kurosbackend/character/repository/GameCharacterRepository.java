package com.kuros.kurosbackend.character.repository;

import com.kuros.kurosbackend.character.domain.GameCharacter;
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
              and (:attribute is null or character.attribute = :attribute)
              and (:weapon is null or character.weaponType = :weapon)
              and (:rarity is null or character.rarity = :rarity)
              and (:keyword is null or lower(character.name) like lower(concat('%', :keyword, '%'))
                   or lower(character.attribute) like lower(concat('%', :keyword, '%'))
                   or lower(character.weaponType) like lower(concat('%', :keyword, '%'))
                   or lower(character.description) like lower(concat('%', :keyword, '%')))
            order by character.sortOrder asc, character.name asc
            """)
    Page<GameCharacter> findVisible(
            @Param("role") String role,
            @Param("attribute") String attribute,
            @Param("weapon") String weapon,
            @Param("rarity") Integer rarity,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    Optional<GameCharacter> findBySlugAndEnabledTrue(String slug);
}
