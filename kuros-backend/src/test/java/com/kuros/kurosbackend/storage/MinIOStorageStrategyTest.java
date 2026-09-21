package com.kuros.kurosbackend.storage;

import com.kuros.kurosbackend.media.storage.MinIOStorageStrategy;
import com.kuros.kurosbackend.media.storage.StorageStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
// Testcontainers 2.x 中 GenericContainer 仍在 org.testcontainers.containers 包
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MinIO 存储策略集成测试。
 *
 * 为什么用 Testcontainers 而不是 Mock？
 * 因为我们要验证的是"MinIO SDK 与真实 MinIO 服务器的交互行为"，
 * Mock 无法覆盖：网络连接、bucket 创建、对象元数据等真实场景。
 *
 * Testcontainers 会自动启动一个 MinIO Docker 容器，测试结束后自动销毁。
 * 这保证了测试隔离性：每个测试类都有干净的 MinIO 环境。
 *
 * 为什么用 GenericContainer 而不是 MinIOContainer？
 * 因为 Testcontainers 2.x 的 minio 模块坐标变了，用 GenericContainer 更稳定。
 *
 * 对应小哈书第七章：测试 → Testcontainers 集成测试。
 *
 * 镜像源使用 quay.io/minio/minio（MinIO 官方 Quay.io 仓库），
 * 避免国内 Docker Hub 拉取受限问题。
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MinIOStorageStrategyTest {

    // Redis 容器：SaToken 需要 Redis 存储会话，不加会导致 Spring 上下文启动失败
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    // Testcontainers MinIO 容器：自动启动、自动销毁、端口随机
    // MinIO 暴露两个端口：9000（API）和 9001（Console）
    @Container
    // 使用 quay.io 镜像源，避免国内 Docker Hub 拉取受限
    static GenericContainer<?> minio = new GenericContainer<>(DockerImageName.parse("quay.io/minio/minio:latest"))
            .withExposedPorts(9000, 9001)
            .withEnv("MINIO_ROOT_USER", "minioadmin")
            .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
            .withCommand("server", "/data", "--console-address", ":9001");

    @DynamicPropertySource
    static void minioProperties(DynamicPropertyRegistry registry) {
        // Redis 动态端口注入（与 KurosBackendApplicationTests 一致）
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        // 将 Testcontainers 动态分配的端口注入 Spring 配置
        // endpoint 格式：http://{host}:{port}
        String endpoint = String.format("http://%s:%d", minio.getHost(), minio.getMappedPort(9000));
        registry.add("app.storage.type", () -> "minio");
        registry.add("app.storage.minio.endpoint", () -> endpoint);
        registry.add("app.storage.minio.access-key", () -> "minioadmin");
        registry.add("app.storage.minio.secret-key", () -> "minioadmin");
        registry.add("app.storage.minio.bucket", () -> "test-bucket");
        registry.add("app.storage.public-base-url", () -> "http://localhost:8080");
    }

    @Autowired
    private StorageStrategy storageStrategy;

    @BeforeEach
    void setUp() {
        // 确保注入的是 MinIOStorageStrategy（不是 LocalStorageStrategy）
        assertThat(storageStrategy).isInstanceOf(MinIOStorageStrategy.class);
    }

    @Test
    void putAndGet_shouldStoreAndRetrieveFile() {
        // Given: 一个测试文件
        String key = "test-image.png";
        byte[] content = "fake png content".getBytes();
        String contentType = "image/png";

        // When: 存储到 MinIO
        storageStrategy.put(key, content, contentType);

        // Then: 能读回来，内容一致
        byte[] retrieved = storageStrategy.get(key);
        assertThat(retrieved).isEqualTo(content);
    }

    @Test
    void getUrl_shouldReturnConsistentFormat() {
        // Given: 一个存储键
        String key = "abc-123.png";

        // When: 获取 URL
        String url = storageStrategy.getUrl(key);

        // Then: URL 格式为 /media/{key}（与 LocalStorageStrategy 一致）
        assertThat(url).isEqualTo("http://localhost:8080/media/" + key);
    }

    @Test
    void delete_shouldRemoveFile() {
        // Given: 已存储的文件
        String key = "to-delete.png";
        storageStrategy.put(key, "content".getBytes(), "image/png");

        // When: 删除
        storageStrategy.delete(key);

        // Then: 再次读取应抛异常（文件不存在）
        assertThatThrownBy(() -> storageStrategy.get(key))
                .isInstanceOf(com.kuros.kurosbackend.shared.exception.FileStorageException.class);
    }

    @Test
    void put_shouldOverwriteExistingFile() {
        // Given: 已存储的文件
        String key = "overwrite-test.png";
        storageStrategy.put(key, "original".getBytes(), "image/png");

        // When: 用新内容覆盖
        storageStrategy.put(key, "updated".getBytes(), "image/png");

        // Then: 读到的是新内容
        byte[] retrieved = storageStrategy.get(key);
        assertThat(new String(retrieved)).isEqualTo("updated");
    }
}
