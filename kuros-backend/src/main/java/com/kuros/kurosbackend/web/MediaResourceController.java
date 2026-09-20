package com.kuros.kurosbackend.web;

import com.kuros.kurosbackend.domain.MediaAsset;
import com.kuros.kurosbackend.exception.ResourceNotFoundException;
import com.kuros.kurosbackend.repository.MediaAssetRepository;
import com.kuros.kurosbackend.storage.StorageStrategy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * MinIO 模式下的静态资源代理 Controller。
 *
 * 为什么需要这个 Controller？
 * 本地模式下，Spring MVC 的 ResourceHandler 直接映射磁盘目录，零性能开销。
 * 但 MinIO 模式下，文件存在对象存储里，本地没有文件，ResourceHandler 找不到。
 * 所以需要这个 Controller 从 MinIO 读取文件再返回给客户端。
 *
 * 为什么加 @ConditionalOnProperty？
 * 本地模式下不需要这个 Controller（ResourceHandler 已经处理了 /media/**）。
 * 如果两个都注册，会产生路由冲突。所以只在 MinIO 模式下才加载。
 *
 * 性能考虑：
 * 当前方案是"后端代理"——每张图都过后端，有性能开销。
 * 这是学习项目的过渡方案，流量极低，完全够用。
 * 未来真正高并发时，会在前面加 Nginx/Gateway 直接代理 MinIO（预签名 URL 或 public bucket）。
 * 这恰好是简历叙事的一部分："先跑通再优化"。
 *
 * 对应小哈书第七章：对象存储 → 文件访问方案。
 */
@RestController
@ConditionalOnProperty(name = "app.storage.type", havingValue = "minio")
public class MediaResourceController {

    private final StorageStrategy storageStrategy;
    private final MediaAssetRepository mediaAssetRepository;

    public MediaResourceController(StorageStrategy storageStrategy,
                                   MediaAssetRepository mediaAssetRepository) {
        this.storageStrategy = storageStrategy;
        this.mediaAssetRepository = mediaAssetRepository;
    }

    /**
     * 代理 /media/{key} 请求，从 MinIO 读取文件返回。
     *
     * @param key 存储键（UUID 文件名，如 "abc-123.png"）
     * @return 文件内容 + Content-Type（浏览器根据 Content-Type 决定如何渲染）
     */
    // 声明所有支持的图片类型，避免客户端发送 Accept: image/jpeg 时返回 406 Not Acceptable
    @GetMapping(value = "/media/{key}", produces = {
            MediaType.IMAGE_PNG_VALUE,
            MediaType.IMAGE_JPEG_VALUE,
            "image/webp"
    })
    public ResponseEntity<byte[]> serve(@PathVariable String key) {
        // 从数据库查询 MediaAsset 获取 Content-Type
        // 为什么不直接从 MinIO metadata 读？因为 MediaAsset 表已有这个信息，避免二次查询
        MediaAsset asset = mediaAssetRepository.findByStorageKey(key)
                .orElseThrow(() -> new ResourceNotFoundException("图片资源不存在"));

        byte[] data = storageStrategy.get(key);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(asset.getContentType()))
                .body(data);
    }
}
