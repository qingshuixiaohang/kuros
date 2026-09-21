package com.kuros.kurosbackend.media.storage;

/**
 * 纯文件 I/O 策略接口。
 *
 * 为什么单独抽出来？
 * 因为"文件存到哪、怎么读写"和"业务校验、元数据管理"是两个独立的关注点。
 * 本地磁盘和 MinIO 对象存储的 I/O 方式完全不同，但业务规则（校验、MediaAsset CRUD）是一样的。
 * 策略模式让两种存储实现可以无缝切换，且未来加阿里云 OSS / 腾讯云 COS 只需新增实现类。
 *
 * 对应小哈书第七章：对象存储 → 策略模式抽象存储层。
 */
public interface StorageStrategy {

    /**
     * 存储文件。
     *
     * @param key         存储键（UUID 文件名，如 "abc-123.png"）
     * @param bytes       文件内容
     * @param contentType MIME 类型（如 "image/png"）
     */
    void put(String key, byte[] bytes, String contentType);

    /**
     * 读取文件内容。
     *
     * @param key 存储键
     * @return 文件字节数组
     */
    byte[] get(String key);

    /**
     * 删除文件。
     *
     * @param key 存储键
     */
    void delete(String key);

    /**
     * 获取文件的公开访问 URL。
     * 用于构建 ImageUploadResponse 返回给前端。
     *
     * @param key 存储键
     * @return 公开访问 URL（如 "/media/abc-123.png"）
     */
    String getUrl(String key);
}
