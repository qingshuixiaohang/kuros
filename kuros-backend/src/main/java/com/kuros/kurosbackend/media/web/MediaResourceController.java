package com.kuros.kurosbackend.media.web;

import com.kuros.kurosbackend.media.domain.MediaAsset;
import com.kuros.kurosbackend.media.domain.MediaAssetStatus;
import com.kuros.kurosbackend.post.repository.PostMediaRepository;
import com.kuros.kurosbackend.shared.exception.ResourceNotFoundException;
import com.kuros.kurosbackend.media.repository.MediaAssetRepository;
import com.kuros.kurosbackend.media.storage.StorageStrategy;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MediaResourceController {

    private final StorageStrategy storageStrategy;
    private final MediaAssetRepository mediaAssetRepository;
    private final PostMediaRepository postMediaRepository;

    public MediaResourceController(StorageStrategy storageStrategy,
                                   MediaAssetRepository mediaAssetRepository,
                                   PostMediaRepository postMediaRepository) {
        this.storageStrategy = storageStrategy;
        this.mediaAssetRepository = mediaAssetRepository;
        this.postMediaRepository = postMediaRepository;
    }

    // 声明所有支持的图片类型，避免客户端发送 Accept: image/jpeg 时返回 406 Not Acceptable
    @GetMapping(value = "/media/{key}", produces = {
            MediaType.IMAGE_PNG_VALUE,
            MediaType.IMAGE_JPEG_VALUE,
            "image/webp"
    })
    public ResponseEntity<byte[]> serve(@PathVariable String key) {
        MediaAsset asset = mediaAssetRepository.findByStorageKey(key)
                .orElseThrow(() -> new ResourceNotFoundException("MEDIA_NOT_FOUND", "图片资源不存在"));
        if (asset.getStatus() == MediaAssetStatus.DELETED
                || (asset.getStatus() == MediaAssetStatus.ATTACHED
                && !postMediaRepository.hasPublishedReference(asset.getId()))) {
            throw new ResourceNotFoundException("MEDIA_NOT_FOUND", "图片资源不存在");
        }

        byte[] data = storageStrategy.get(key);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(asset.getContentType()))
                .body(data);
    }
}
