package com.kuros.kurosbackend.storage;

import com.kuros.kurosbackend.api.ImageUploadResponse;
import com.kuros.kurosbackend.exception.AuthRequestException;
import com.kuros.kurosbackend.exception.FileStorageException;
import com.kuros.kurosbackend.domain.MediaAsset;
import com.kuros.kurosbackend.domain.MediaAssetStatus;
import com.kuros.kurosbackend.repository.MediaAssetRepository;
import com.kuros.kurosbackend.exception.ForbiddenException;
import com.kuros.kurosbackend.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class LocalImageStorageService implements ImageStorageService {

    private static final long MAX_SIZE = 10 * 1024 * 1024;
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/webp", "webp"
    );

    private final Path root;
    private final String publicBaseUrl;
    private final MediaAssetRepository mediaAssetRepository;

    public LocalImageStorageService(
            @Value("${app.storage.local-dir:${user.dir}/storage}") String localDir,
            @Value("${app.storage.public-base-url:http://localhost:8080}") String publicBaseUrl,
            MediaAssetRepository mediaAssetRepository
    ) {
        this.root = Paths.get(localDir).toAbsolutePath().normalize();
        this.publicBaseUrl = publicBaseUrl.replaceAll("/$", "");
        this.mediaAssetRepository = mediaAssetRepository;
    }

    @Override
    public ImageUploadResponse store(MultipartFile file, String ownerId) {
        if (file == null || file.isEmpty()) {
            throw new AuthRequestException("IMAGE_REQUIRED", "请选择要上传的图片");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new AuthRequestException("IMAGE_TOO_LARGE", "图片大小不能超过 10 MB");
        }

        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        String extension = EXTENSIONS.get(contentType);
        if (extension == null || !hasAllowedExtension(file.getOriginalFilename(), extension)) {
            throw new AuthRequestException("IMAGE_TYPE_INVALID", "仅支持 PNG、JPEG 或 WebP 图片");
        }
        try {
            if (!hasValidSignature(file.getBytes(), contentType)) {
                throw new AuthRequestException("IMAGE_CONTENT_INVALID", "图片内容与声明的格式不匹配");
            }
        } catch (IOException exception) {
            throw new FileStorageException("图片读取失败", exception);
        }

        String storedName = UUID.randomUUID() + "." + extension;
        try {
            Files.createDirectories(root);
            Path target = root.resolve(storedName);
            file.transferTo(target);
            MediaAsset asset = mediaAssetRepository.save(MediaAsset.temporary(
                    ownerId, storedName, file.getOriginalFilename(), contentType, file.getSize(), java.time.LocalDateTime.now()
            ));
            return new ImageUploadResponse(asset.getId(), publicBaseUrl + "/media/" + storedName,
                    file.getOriginalFilename(), contentType, file.getSize());
        } catch (IOException | IllegalStateException exception) {
            throw new FileStorageException("图片保存失败", exception);
        }
    }

    public Path root() {
        return root;
    }

    @Override
    public void delete(String assetId, String ownerId) {
        MediaAsset asset = mediaAssetRepository.findById(assetId)
                .orElseThrow(() -> new ResourceNotFoundException("图片资源不存在"));
        if (!ownerId.equals(asset.getOwnerId())) throw new ForbiddenException("不能删除其他用户的图片");
        if (asset.getStatus() == MediaAssetStatus.ATTACHED) {
            throw new AuthRequestException("MEDIA_ATTACHED", "已发布帖子的图片不能单独删除");
        }
        try {
            Files.deleteIfExists(root.resolve(asset.getStorageKey()).normalize());
            asset.markDeleted();
            mediaAssetRepository.save(asset);
        } catch (IOException exception) {
            throw new FileStorageException("图片删除失败", exception);
        }
    }

    @Override
    public int cleanupTemporaryAssets(LocalDateTime cutoff) {
        List<MediaAsset> expired = mediaAssetRepository.findByStatusAndCreatedAtBefore(MediaAssetStatus.TEMPORARY, cutoff);
        for (MediaAsset asset : expired) {
            try {
                Files.deleteIfExists(root.resolve(asset.getStorageKey()).normalize());
                asset.markDeleted();
            } catch (IOException exception) {
                throw new FileStorageException("临时图片清理失败", exception);
            }
        }
        mediaAssetRepository.saveAll(expired);
        return expired.size();
    }

    private boolean hasAllowedExtension(String originalName, String expectedExtension) {
        if (originalName == null) return false;
        String normalized = originalName.toLowerCase(Locale.ROOT);
        return normalized.endsWith("." + expectedExtension) || (expectedExtension.equals("jpg") && normalized.endsWith(".jpeg"));
    }

    private boolean hasValidSignature(byte[] bytes, String contentType) {
        if ("image/png".equals(contentType)) {
            return bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47
                    && bytes[4] == 0x0D && bytes[5] == 0x0A && bytes[6] == 0x1A && bytes[7] == 0x0A;
        }
        if ("image/jpeg".equals(contentType)) {
            return bytes.length >= 3 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8 && bytes[2] == (byte) 0xFF;
        }
        return bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    }
}
