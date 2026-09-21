package com.kuros.kurosbackend.post.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "post_media", uniqueConstraints = {
        @UniqueConstraint(name = "uq_post_media_asset_order", columnNames = {"post_id", "sort_order"}),
        @UniqueConstraint(name = "uq_post_media_post_asset", columnNames = {"post_id", "asset_id"})
})
public class PostMedia {

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Column(name = "post_id", length = 36, nullable = false)
    private String postId;

    @Column(name = "asset_id", length = 36, nullable = false, unique = true)
    private String assetId;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected PostMedia() {
    }

    public static PostMedia create(String postId, String assetId, int sortOrder, LocalDateTime now) {
        PostMedia media = new PostMedia();
        media.id = UUID.randomUUID().toString();
        media.postId = postId;
        media.assetId = assetId;
        media.sortOrder = sortOrder;
        media.createdAt = now;
        return media;
    }

    public String getId() { return id; }
    public String getPostId() { return postId; }
    public String getAssetId() { return assetId; }
    public int getSortOrder() { return sortOrder; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
