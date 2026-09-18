package com.kuros.kurosbackend.service;

import com.kuros.kurosbackend.api.CharacterResponse;
import com.kuros.kurosbackend.api.PageMeta;
import com.kuros.kurosbackend.api.PageResult;
import com.kuros.kurosbackend.domain.GameCharacter;
import com.kuros.kurosbackend.exception.ResourceNotFoundException;
import com.kuros.kurosbackend.repository.GameCharacterRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CharacterCatalogService {

    private static final int MAX_PAGE_SIZE = 50;
    private final GameCharacterRepository repository;

    public CharacterCatalogService(GameCharacterRepository repository) {
        this.repository = repository;
    }

    public PageResult<CharacterResponse> findVisible(int page, int pageSize, String role, String keyword) {
        int normalizedPage = Math.max(page, 1);
        int normalizedPageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        Page<GameCharacter> result = repository.findVisible(
                blankToNull(role), blankToNull(keyword), PageRequest.of(normalizedPage - 1, normalizedPageSize)
        );
        return new PageResult<>(
                result.getContent().stream().map(this::toResponse).toList(),
                new PageMeta(normalizedPage, normalizedPageSize, result.getTotalElements(), result.getTotalPages())
        );
    }

    public CharacterResponse findVisibleBySlug(String slug) {
        return repository.findBySlugAndEnabledTrue(slug)
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("CHARACTER_NOT_FOUND", "角色不存在或已下架"));
    }

    private CharacterResponse toResponse(GameCharacter character) {
        return new CharacterResponse(
                character.getId(), character.getSlug(), character.getName(), character.getRole(), character.getRarity(),
                character.getAttribute(), character.getWeaponType(), character.getVersion(), character.getImageUrl(),
                character.getDescription()
        );
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
