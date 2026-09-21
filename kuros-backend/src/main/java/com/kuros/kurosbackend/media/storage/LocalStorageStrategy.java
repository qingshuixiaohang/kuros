package com.kuros.kurosbackend.media.storage;

import com.kuros.kurosbackend.shared.exception.FileStorageException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 本地磁盘存储策略。
 *
 * 为什么开发环境用本地磁盘而不是 MinIO？
 * 1. 快速启动：不需要额外拉取 MinIO 镜像
 * 2. 调试方便：文件直接在磁盘上，可以用文件管理器查看
 * 3. 零依赖：本地开发只需 MySQL + Redis（或连远程 Redis）
 *
 * 生产环境切换到 MinIO 只需改一行配置：app.storage.type=minio
 *
 * @ConditionalOnProperty：默认 local，只有 type=local 或未配置时才加载这个 Bean。
 * 对应小哈书第七章：开发环境用本地存储，生产环境用 MinIO。
 */
@Component
@ConditionalOnProperty(name = "app.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalStorageStrategy implements StorageStrategy {

    private final Path root;
    private final String publicBaseUrl;

    public LocalStorageStrategy(
            @Value("${app.storage.local-dir:${user.dir}/storage}") String localDir,
            @Value("${app.storage.public-base-url:http://localhost:8080}") String publicBaseUrl
    ) {
        this.root = Paths.get(localDir).toAbsolutePath().normalize();
        this.publicBaseUrl = publicBaseUrl.replaceAll("/$", "");
    }

    /**
     * 存储文件到本地磁盘。
     * 为什么先创建目录再写文件？因为首次启动时 storage 目录可能不存在。
     */
    @Override
    public void put(String key, byte[] bytes, String contentType) {
        try {
            Files.createDirectories(root);
            Path target = root.resolve(key);
            // 安全检查：防止路径穿越攻击（如 key = "../../etc/passwd"）
            if (!target.normalize().startsWith(root)) {
                throw new FileStorageException("非法的文件路径");
            }
            Files.write(target, bytes);
        } catch (IOException exception) {
            throw new FileStorageException("图片保存失败", exception);
        }
    }

    /**
     * 从本地磁盘读取文件。
     * MinIO 模式下的代理 Controller 也需要读取文件，但走的是 MinIO SDK。
     */
    @Override
    public byte[] get(String key) {
        try {
            Path target = root.resolve(key).normalize();
            // 安全检查：防止路径穿越
            if (!target.startsWith(root)) {
                throw new FileStorageException("非法的文件路径");
            }
            return Files.readAllBytes(target);
        } catch (IOException exception) {
            throw new FileStorageException("图片读取失败", exception);
        }
    }

    /**
     * 从本地磁盘删除文件。
     * deleteIfExists 而不是 delete：文件不存在时不抛异常（幂等性）。
     */
    @Override
    public void delete(String key) {
        try {
            Path target = root.resolve(key).normalize();
            if (!target.startsWith(root)) {
                throw new FileStorageException("非法的文件路径");
            }
            Files.deleteIfExists(target);
        } catch (IOException exception) {
            throw new FileStorageException("图片删除失败", exception);
        }
    }

    /**
     * 返回文件的公开访问 URL。
     * 本地模式下，URL 格式为：{publicBaseUrl}/media/{key}
     * 这个 URL 由 Spring MVC 的 ResourceHandler 映射到本地目录。
     */
    @Override
    public String getUrl(String key) {
        return publicBaseUrl + "/media/" + key;
    }

    /**
     * 返回本地存储根目录。
     * MediaResourceConfig 需要这个方法来注册 ResourceHandler。
     * 只有 LocalStorageStrategy 需要这个方法，MinIOStorageStrategy 不需要。
     */
    public Path root() {
        return root;
    }
}
