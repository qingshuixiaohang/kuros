package com.kuros.kurosbackend.media.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "media_assets")
public class MediaAsset {

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Column(name = "owner_id", length = 36, nullable = false)
    private String ownerId;

    @Column(name = "storage_key", length = 255, nullable = false, unique = true)
    private String storageKey;

    @Column(name = "original_name", length = 255, nullable = false)
    private String originalName;

    @Column(name = "content_type", length = 64, nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private MediaAssetStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "attached_at")
    private LocalDateTime attachedAt;

    protected MediaAsset() {
    }

    public static MediaAsset temporary(
            String ownerId,
            String storageKey,
            String originalName,
            String contentType,
            long sizeBytes,
            LocalDateTime now
    ) {
        MediaAsset asset = new MediaAsset();
        asset.id = UUID.randomUUID().toString();
        asset.ownerId = ownerId;
        asset.storageKey = storageKey;
        asset.originalName = originalName;
        asset.contentType = contentType;
        asset.sizeBytes = sizeBytes;
        asset.status = MediaAssetStatus.TEMPORARY;
        asset.createdAt = now;
        return asset;
    }

    public void attach(LocalDateTime now) {
        status = MediaAssetStatus.ATTACHED;
        if (attachedAt == null) attachedAt = now;
    }

    public void markDeleted() {
        status = MediaAssetStatus.DELETED;
    }

    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getStorageKey() { return storageKey; }
    public String getOriginalName() { return originalName; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public MediaAssetStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getAttachedAt() { return attachedAt; }
}
