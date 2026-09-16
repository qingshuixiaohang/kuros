package com.kuros.kurosbackend.storage;

import com.kuros.kurosbackend.api.ImageUploadResponse;
import com.kuros.kurosbackend.exception.AuthRequestException;
import com.kuros.kurosbackend.exception.FileStorageException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class LocalImageStorageService implements ImageStorageService {

    private static final long MAX_SIZE = 5 * 1024 * 1024;
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/webp", "webp"
    );

    private final Path root;
    private final String publicBaseUrl;

    public LocalImageStorageService(
            @Value("${app.storage.local-dir:${user.dir}/storage}") String localDir,
            @Value("${app.storage.public-base-url:http://localhost:8080}") String publicBaseUrl
    ) {
        this.root = Paths.get(localDir).toAbsolutePath().normalize();
        this.publicBaseUrl = publicBaseUrl.replaceAll("/$", "");
    }

    @Override
    public ImageUploadResponse store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new AuthRequestException("IMAGE_REQUIRED", "请选择要上传的图片");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new AuthRequestException("IMAGE_TOO_LARGE", "图片大小不能超过 5 MB");
        }

        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        String extension = EXTENSIONS.get(contentType);
        if (extension == null || !hasAllowedExtension(file.getOriginalFilename(), extension)) {
            throw new AuthRequestException("IMAGE_TYPE_INVALID", "仅支持 PNG、JPEG 或 WebP 图片");
        }

        String storedName = UUID.randomUUID() + "." + extension;
        try {
            Files.createDirectories(root);
            file.transferTo(root.resolve(storedName));
        } catch (IOException | IllegalStateException exception) {
            throw new FileStorageException("图片保存失败", exception);
        }
        return new ImageUploadResponse(publicBaseUrl + "/media/" + storedName, file.getOriginalFilename(), contentType, file.getSize());
    }

    public Path root() {
        return root;
    }

    private boolean hasAllowedExtension(String originalName, String expectedExtension) {
        if (originalName == null) return false;
        String normalized = originalName.toLowerCase(Locale.ROOT);
        return normalized.endsWith("." + expectedExtension) || (expectedExtension.equals("jpg") && normalized.endsWith(".jpeg"));
    }
}
