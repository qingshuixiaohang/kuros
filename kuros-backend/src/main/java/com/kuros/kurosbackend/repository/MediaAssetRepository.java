package com.kuros.kurosbackend.repository;

import com.kuros.kurosbackend.domain.MediaAsset;
import com.kuros.kurosbackend.domain.MediaAssetStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, String> {

    List<MediaAsset> findByStatusAndCreatedAtBefore(MediaAssetStatus status, LocalDateTime cutoff);
}
