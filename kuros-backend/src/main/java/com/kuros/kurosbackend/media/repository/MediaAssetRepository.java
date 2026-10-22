package com.kuros.kurosbackend.media.repository;

import com.kuros.kurosbackend.media.domain.MediaAsset;
import com.kuros.kurosbackend.media.domain.MediaAssetStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.LockModeType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Collection;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, String> {

    String PUBLISHED_REFERENCE = "exists (select post.id from CommunityPost post "
            + "where post.status = com.kuros.kurosbackend.post.domain.PostStatus.PUBLISHED "
            + "and (locate(concat('/media/', asset.storageKey), post.content) > 0 "
            + "or exists (select media.id from PostMedia media where media.postId = post.id and media.assetId = asset.id)))";

    String CLEANUP_ELIGIBLE = "asset.storageDeletedAt is null and "
            + "(asset.status = com.kuros.kurosbackend.media.domain.MediaAssetStatus.DELETED "
            + "or asset.status = com.kuros.kurosbackend.media.domain.MediaAssetStatus.ATTACHED "
            + "or (asset.status = com.kuros.kurosbackend.media.domain.MediaAssetStatus.TEMPORARY and asset.createdAt < :cutoff)) "
            + "and not " + PUBLISHED_REFERENCE;

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select asset from MediaAsset asset where asset.id in :ids order by asset.id")
    List<MediaAsset> findAllByIdForUpdate(@Param("ids") Collection<String> ids);

    @Query("select asset from MediaAsset asset where asset.id > :afterId and " + CLEANUP_ELIGIBLE + " order by asset.id")
    List<MediaAsset> findCleanupCandidates(@Param("cutoff") LocalDateTime cutoff,
                                          @Param("afterId") String afterId, Pageable page);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update MediaAsset asset set asset.status = com.kuros.kurosbackend.media.domain.MediaAssetStatus.DELETED "
            + "where asset.id = :id and " + CLEANUP_ELIGIBLE)
    int claimCleanup(@Param("id") String id, @Param("cutoff") LocalDateTime cutoff);

    @Query("select count(asset) > 0 from MediaAsset asset where asset.id = :id and " + PUBLISHED_REFERENCE)
    boolean hasPublishedReference(@Param("id") String id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update MediaAsset asset set asset.status = com.kuros.kurosbackend.media.domain.MediaAssetStatus.ATTACHED "
            + "where asset.status = com.kuros.kurosbackend.media.domain.MediaAssetStatus.DELETED and " + PUBLISHED_REFERENCE)
    int repairDeletedAssetsWithPublishedReferences();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update MediaAsset asset set asset.storageDeletedAt = :deletedAt "
            + "where asset.id = :id and asset.status = com.kuros.kurosbackend.media.domain.MediaAssetStatus.DELETED")
    int recordStorageDeletion(@Param("id") String id, @Param("deletedAt") LocalDateTime deletedAt);

    List<MediaAsset> findByStatusAndCreatedAtBefore(MediaAssetStatus status, LocalDateTime cutoff);

    /**
     * 按存储键查找媒体资产。
     * MinIO 模式下的 MediaResourceController 需要这个方法：
     * 前端请求 /media/{key}，需要从数据库查询 Content-Type。
     */
    Optional<MediaAsset> findByStorageKey(String storageKey);
}
