package com.kuros.kurosbackend.media;

import com.kuros.kurosbackend.media.api.ImageUploadResponse;
import com.kuros.kurosbackend.media.domain.MediaAsset;
import com.kuros.kurosbackend.media.domain.MediaAssetStatus;
import com.kuros.kurosbackend.media.repository.MediaAssetRepository;
import com.kuros.kurosbackend.media.storage.DefaultMediaAssetService;
import com.kuros.kurosbackend.media.storage.StorageStrategy;
import com.kuros.kurosbackend.shared.exception.FileStorageException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaUploadConsistencyTest {

    @Test
    void 元数据保存失败时不写对象存储() {
        RecordingStorage storage = new RecordingStorage();
        DefaultMediaAssetService service = new DefaultMediaAssetService(
                storage,
                repository(asset -> {
                    throw new DataIntegrityViolationException("数据库不可用");
                })
        );
        MockMultipartFile file = pngFile();

        assertThatThrownBy(() -> service.store(file, "owner-1"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(storage.putCount).isZero();
    }

    @Test
    void 对象存储失败时标记临时资产删除并传播存储异常() {
        List<MediaAssetStatus> savedStatuses = new ArrayList<>();
        RecordingStorage storage = new RecordingStorage();
        storage.failure = new IllegalStateException("对象存储不可用");
        DefaultMediaAssetService service = new DefaultMediaAssetService(
                storage,
                repository(asset -> {
                    savedStatuses.add(asset.getStatus());
                    return asset;
                })
        );
        MockMultipartFile file = pngFile();

        assertThatThrownBy(() -> service.store(file, "owner-1"))
                .isInstanceOf(FileStorageException.class)
                .hasCause(storage.failure);

        assertThat(savedStatuses).containsExactly(
                MediaAssetStatus.TEMPORARY,
                MediaAssetStatus.DELETED
        );
    }

    @Test
    void 补偿保存失败时不覆盖原始存储异常() {
        List<MediaAssetStatus> attemptedStatuses = new ArrayList<>();
        List<MediaAssetStatus> persistedStatuses = new ArrayList<>();
        AtomicInteger saveCount = new AtomicInteger();
        RecordingStorage storage = new RecordingStorage();
        storage.failure = new IllegalStateException("对象存储不可用");
        DefaultMediaAssetService service = new DefaultMediaAssetService(
                storage,
                repository(asset -> {
                    attemptedStatuses.add(asset.getStatus());
                    if (saveCount.getAndIncrement() > 0) {
                        throw new DataIntegrityViolationException("补偿保存失败");
                    }
                    persistedStatuses.add(asset.getStatus());
                    return asset;
                })
        );

        assertThatThrownBy(() -> service.store(pngFile(), "owner-1"))
                .isInstanceOf(FileStorageException.class)
                .hasCause(storage.failure);

        assertThat(attemptedStatuses).containsExactly(
                MediaAssetStatus.TEMPORARY,
                MediaAssetStatus.DELETED
        );
        assertThat(persistedStatuses).containsExactly(MediaAssetStatus.TEMPORARY);
    }

    @Test
    void 成功上传先登记临时资产再写一次对象存储() {
        List<String> events = new ArrayList<>();
        List<MediaAssetStatus> savedStatuses = new ArrayList<>();
        RecordingStorage storage = new RecordingStorage();
        storage.events = events;
        DefaultMediaAssetService service = new DefaultMediaAssetService(
                storage,
                repository(asset -> {
                    savedStatuses.add(asset.getStatus());
                    events.add("save:" + asset.getStatus());
                    return asset;
                })
        );
        MockMultipartFile file = pngFile();

        ImageUploadResponse response = service.store(file, "owner-1");

        assertThat(savedStatuses).containsExactly(MediaAssetStatus.TEMPORARY);
        assertThat(storage.putCount).isEqualTo(1);
        assertThat(events).containsExactly("save:TEMPORARY", "put");
        assertThat(response.assetId()).isNotBlank();
        assertThat(response.url())
                .startsWith("http://example.test/media/")
                .endsWith(".png");
        assertThat(response.originalName()).isEqualTo("cover.png");
        assertThat(response.contentType()).isEqualTo("image/png");
        assertThat(response.size()).isEqualTo(8);
    }

    private MediaAssetRepository repository(Function<MediaAsset, MediaAsset> saveBehavior) {
        return (MediaAssetRepository) Proxy.newProxyInstance(
                MediaAssetRepository.class.getClassLoader(),
                new Class<?>[]{MediaAssetRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("save")) {
                        return saveBehavior.apply((MediaAsset) args[0]);
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private MockMultipartFile pngFile() {
        return new MockMultipartFile(
                "file",
                "cover.png",
                "image/png",
                new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}
        );
    }

    private static final class RecordingStorage implements StorageStrategy {
        private int putCount;
        private RuntimeException failure;
        private List<String> events;

        @Override
        public void put(String key, byte[] bytes, String contentType) {
            putCount++;
            if (events != null) {
                events.add("put");
            }
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public byte[] get(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getUrl(String key) {
            return "http://example.test/media/" + key;
        }
    }
}
