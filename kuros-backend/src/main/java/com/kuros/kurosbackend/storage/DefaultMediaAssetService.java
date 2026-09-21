package com.kuros.kurosbackend.storage;

import com.kuros.kurosbackend.api.ImageUploadResponse;
import com.kuros.kurosbackend.domain.MediaAsset;
import com.kuros.kurosbackend.domain.MediaAssetStatus;
import com.kuros.kurosbackend.shared.exception.AuthRequestException;
import com.kuros.kurosbackend.shared.exception.FileStorageException;
import com.kuros.kurosbackend.shared.exception.ForbiddenException;
import com.kuros.kurosbackend.shared.exception.ResourceNotFoundException;
import com.kuros.kurosbackend.repository.MediaAssetRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 媒体资产业务服务默认实现。
 *
 * 为什么叫 DefaultMediaAssetService 而不是 LocalImageStorageService？
 * 1. 它不再只处理"本地"存储——通过注入不同的 StorageStrategy，可以存到本地或 MinIO
 * 2. 它的职责是"业务逻辑"（校验、元数据管理、权限控制），而不是"存储 I/O"
 *
 * 重构前：这个类直接操作文件系统（Files.write / file.transferTo）
 * 重构后：文件 I/O 委托给 StorageStrategy，本类只关心业务规则
 *
 * 对应小哈书第七章：策略模式 → 业务逻辑与存储实现解耦。
 */
@Service
public class DefaultMediaAssetService implements MediaAssetService {

    private static final long MAX_SIZE = 10 * 1024 * 1024;
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/webp", "webp"
    );

    private final StorageStrategy storageStrategy;
    private final MediaAssetRepository mediaAssetRepository;

    public DefaultMediaAssetService(
            StorageStrategy storageStrategy,
            MediaAssetRepository mediaAssetRepository
    ) {
        this.storageStrategy = storageStrategy;
        this.mediaAssetRepository = mediaAssetRepository;
    }

    /**
     * 上传图片。
     * 流程：校验 → UUID 重命名 → 存储 → 记录元数据 → 返回响应
     *
     * 为什么校验逻辑放在业务层而不是 Strategy？
     * 因为校验规则（大小/类型/Magic Number）是业务规则，跟存储介质无关。
     * 无论存本地还是 MinIO，校验逻辑都一样。
     */
    @Override
    public ImageUploadResponse store(MultipartFile file, String ownerId) {
        // 1. 文件校验（业务规则，与存储无关）
        validateFile(file);

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

        // 2. UUID 重命名（避免文件名冲突 + 防止路径穿越）
        String storedName = UUID.randomUUID() + "." + extension;

        // 3. 委托 Strategy 存储（本地 or MinIO）
        try {
            storageStrategy.put(storedName, file.getBytes(), contentType);
        } catch (Exception exception) {
            throw new FileStorageException("图片保存失败", exception);
        }

        // 4. 记录元数据到数据库
        MediaAsset asset = mediaAssetRepository.save(MediaAsset.temporary(
                ownerId, storedName, file.getOriginalFilename(), contentType, file.getSize(), LocalDateTime.now()
        ));

        // 5. 返回响应（URL 由 Strategy 生成，保证与存储位置一致）
        return new ImageUploadResponse(
                asset.getId(),
                storageStrategy.getUrl(storedName),
                file.getOriginalFilename(),
                contentType,
                file.getSize()
        );
    }

    /**
     * 删除图片。
     * 权限校验：只能删除自己的图片；已发布帖子的图片不能单独删除。
     */
    @Override
    public void delete(String assetId, String ownerId) {
        MediaAsset asset = mediaAssetRepository.findById(assetId)
                .orElseThrow(() -> new ResourceNotFoundException("图片资源不存在"));
        if (!ownerId.equals(asset.getOwnerId())) {
            throw new ForbiddenException("不能删除其他用户的图片");
        }
        if (asset.getStatus() == MediaAssetStatus.ATTACHED) {
            throw new AuthRequestException("MEDIA_ATTACHED", "已发布帖子的图片不能单独删除");
        }

        // 委托 Strategy 删除物理文件
        storageStrategy.delete(asset.getStorageKey());

        asset.markDeleted();
        mediaAssetRepository.save(asset);
    }

    /**
     * 清理过期的临时图片（上传后未关联帖子的孤儿文件）。
     * 定时任务调用（后续 XXL-JOB 切片会接入）。
     */
    @Override
    public int cleanupTemporaryAssets(LocalDateTime cutoff) {
        List<MediaAsset> expired = mediaAssetRepository.findByStatusAndCreatedAtBefore(MediaAssetStatus.TEMPORARY, cutoff);
        for (MediaAsset asset : expired) {
            storageStrategy.delete(asset.getStorageKey());
            asset.markDeleted();
        }
        mediaAssetRepository.saveAll(expired);
        return expired.size();
    }

    // ==================== 私有方法：文件校验 ====================

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new AuthRequestException("IMAGE_REQUIRED", "请选择要上传的图片");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new AuthRequestException("IMAGE_TOO_LARGE", "图片大小不能超过 10 MB");
        }
    }

    private boolean hasAllowedExtension(String originalName, String expectedExtension) {
        if (originalName == null) return false;
        String normalized = originalName.toLowerCase(Locale.ROOT);
        return normalized.endsWith("." + expectedExtension) || (expectedExtension.equals("jpg") && normalized.endsWith(".jpeg"));
    }

    /**
     * Magic Number 校验：防止"改了扩展名的非图片文件"。
     * 例如：把 .exe 改名为 .png 上传，Magic Number 不匹配会被拒绝。
     */
    private boolean hasValidSignature(byte[] bytes, String contentType) {
        if ("image/png".equals(contentType)) {
            return bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47
                    && bytes[4] == 0x0D && bytes[5] == 0x0A && bytes[6] == 0x1A && bytes[7] == 0x0A;
        }
        if ("image/jpeg".equals(contentType)) {
            return bytes.length >= 3 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8 && bytes[2] == (byte) 0xFF;
        }
        // WebP: RIFF....WEBP
        return bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    }
}
