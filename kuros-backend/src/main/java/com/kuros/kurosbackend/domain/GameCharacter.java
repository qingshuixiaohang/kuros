package com.kuros.kurosbackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "game_characters")
public class GameCharacter {

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Column(length = 80, nullable = false, unique = true)
    private String slug;

    @Column(length = 64, nullable = false)
    private String name;

    @Column(length = 32, nullable = false)
    private String role;

    @Column(nullable = false)
    private int rarity;

    @Column(name = "attribute_name", length = 32, nullable = false)
    private String attribute;

    @Column(name = "weapon_type", length = 32, nullable = false)
    private String weaponType;

    @Column(length = 32, nullable = false)
    private String version;

    @Column(name = "image_url", length = 512, nullable = false)
    private String imageUrl;

    @Column(length = 500, nullable = false)
    private String description;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected GameCharacter() {
    }

    public String getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    public String getName() {
        return name;
    }

    public String getRole() {
        return role;
    }

    public int getRarity() {
        return rarity;
    }

    public String getAttribute() {
        return attribute;
    }

    public String getWeaponType() {
        return weaponType;
    }

    public String getVersion() {
        return version;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public String getDescription() {
        return description;
    }
}
