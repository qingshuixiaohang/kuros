package com.kuros.kurosbackend.character.api;

public record CharacterResponse(
        String id,
        String slug,
        String name,
        String role,
        int rarity,
        String attribute,
        String weaponType,
        String version,
        String imageUrl,
        String description
) {
}
