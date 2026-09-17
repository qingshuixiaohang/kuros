package com.kuros.kurosbackend.storage;

import com.kuros.kurosbackend.api.ImageUploadResponse;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;

public interface ImageStorageService {

    ImageUploadResponse store(MultipartFile file, String ownerId);

    void delete(String assetId, String ownerId);

    int cleanupTemporaryAssets(LocalDateTime cutoff);
}
