package com.kuros.kurosbackend.storage;

import com.kuros.kurosbackend.exception.FileStorageException;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * MinIO 对象存储策略。
 *
 * 什么是 MinIO？
 * MinIO 是一个开源的 S3 兼容对象存储服务器，可以私有化部署。
 * 相比 AWS S3，MinIO 可以跑在自己的机器上（或 Docker 容器里），适合开发和测试环境。
 *
 * 什么是对象存储？
 * 传统文件系统是树状结构（目录/子目录/文件），对象存储是扁平结构（bucket + key）。
 * 例如：bucket="kuros-media", key="abc-123.png" 就是一个对象。
 * 对象存储的优势：水平扩展、高可用、适合存储大量非结构化文件（图片/视频/文档）。
 *
 * 为什么生产环境用 MinIO 而不是本地磁盘？
 * 1. 分布式：多台后端实例共享同一个 MinIO，文件不需要在实例间同步
 * 2. 高可用：MinIO 支持纠删码，部分磁盘损坏也不丢数据
 * 3. 无限容量：本地磁盘受限于单机，MinIO 可以扩展到 PB 级别
 *
 * 对应小哈书第七章：MinIO 对象存储集成。
 */
@Component
@ConditionalOnProperty(name = "app.storage.type", havingValue = "minio")
public class MinIOStorageStrategy implements StorageStrategy {

    private final MinioClient minioClient;
    private final String bucket;
    private final String publicBaseUrl;

    public MinIOStorageStrategy(
            @Value("${app.storage.minio.endpoint}") String endpoint,
            @Value("${app.storage.minio.access-key}") String accessKey,
            @Value("${app.storage.minio.secret-key}") String secretKey,
            @Value("${app.storage.minio.bucket}") String bucket,
            @Value("${app.storage.public-base-url:http://localhost:8080}") String publicBaseUrl
    ) {
        // 创建 MinIO 客户端（连接对象存储服务器）
        this.minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        this.bucket = bucket;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/$", "");

        // 启动时自动创建 bucket（如果不存在）
        // 为什么在构造函数做？因为 bucket 是存储的前提，不存在就什么都存不了
        ensureBucketExists();
    }

    /**
     * 存储文件到 MinIO。
     *
     * @param key         对象键（UUID 文件名，如 "abc-123.png"）
     * @param bytes       文件内容
     * @param contentType MIME 类型（MinIO 会存为 object metadata，下载时返回）
     */
    @Override
    public void put(String key, byte[] bytes, String contentType) {
        try (InputStream inputStream = new ByteArrayInputStream(bytes)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(inputStream, bytes.length, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception exception) {
            throw new FileStorageException("图片保存到 MinIO 失败", exception);
        }
    }

    /**
     * 从 MinIO 读取文件内容。
     * MediaResourceController 代理 /media/** 请求时会调用此方法。
     */
    @Override
    public byte[] get(String key) {
        try (InputStream stream = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(key)
                .build())) {
            return stream.readAllBytes();
        } catch (Exception exception) {
            throw new FileStorageException("从 MinIO 读取图片失败", exception);
        }
    }

    /**
     * 从 MinIO 删除文件。
     * 对象存储的删除是幂等的：删除不存在的对象不会报错。
     */
    @Override
    public void delete(String key) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .build());
        } catch (Exception exception) {
            throw new FileStorageException("从 MinIO 删除图片失败", exception);
        }
    }

    /**
     * 返回文件的公开访问 URL。
     * 与 LocalStorageStrategy 保持一致：/media/{key}
     * 这个 URL 由 MediaResourceController 代理处理。
     */
    @Override
    public String getUrl(String key) {
        return publicBaseUrl + "/media/" + key;
    }

    /**
     * 确保 bucket 存在，不存在则创建。
     * 为什么不用 mc（MinIO Client CLI）提前创建？
     * 因为应用启动时自动创建更方便，减少手动配置步骤。
     */
    private void ensureBucketExists() {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                    .bucket(bucket)
                    .build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder()
                        .bucket(bucket)
                        .build());
            }
        } catch (Exception exception) {
            throw new FileStorageException("MinIO bucket 初始化失败", exception);
        }
    }
}
