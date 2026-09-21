package com.kuros.kurosbackend.media.repository;

import com.kuros.kurosbackend.media.domain.MediaAsset;
import com.kuros.kurosbackend.media.domain.MediaAssetStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, String> {

    List<MediaAsset> findByStatusAndCreatedAtBefore(MediaAssetStatus status, LocalDateTime cutoff);

    /**
     * 按存储键查找媒体资产。
     * MinIO 模式下的 MediaResourceController 需要这个方法：
     * 前端请求 /media/{key}，需要从数据库查询 Content-Type。
     */
    Optional<MediaAsset> findByStorageKey(String storageKey);
}
