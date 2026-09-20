package com.kuros.kurosbackend.storage;

import com.kuros.kurosbackend.api.ImageUploadResponse;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;

/**
 * 媒体资产业务服务接口。
 *
 * 为什么叫 MediaAssetService 而不是 ImageStorageService？
 * 因为它的职责是"媒体资产的生命周期管理"（上传/删除/清理），
 * 而不仅仅是"存储"。存储 I/O 已经下沉到 StorageStrategy。
 *
 * 方法签名保持不变，Controller 只需改 import 和类型引用。
 */
public interface MediaAssetService {

    ImageUploadResponse store(MultipartFile file, String ownerId);

    void delete(String assetId, String ownerId);

    int cleanupTemporaryAssets(LocalDateTime cutoff);
}
