package com.kuros.kurosbackend.media;

import com.kuros.kurosbackend.media.domain.MediaAsset;
import com.kuros.kurosbackend.media.repository.MediaAssetRepository;
import com.kuros.kurosbackend.media.storage.DefaultMediaAssetService;
import com.kuros.kurosbackend.media.storage.MediaAssetService;
import com.kuros.kurosbackend.media.storage.StorageStrategy;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Timestamp;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MediaUploadTransactionVisibilityTest {

    @Test
    void 上传在调用方事务内仍先提交临时元数据() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(TestConfiguration.class)) {
            MediaAssetService service = context.getBean(MediaAssetService.class);
            VisibilityStorage storage = context.getBean(VisibilityStorage.class);
            TransactionTemplate outerTransaction = new TransactionTemplate(
                    context.getBean(PlatformTransactionManager.class)
            );

            outerTransaction.executeWithoutResult(status ->
                    service.store(pngFile(), "owner-1")
            );

            assertThat(storage.visibleDuringPut).hasValue(1);
        }
    }

    private static MockMultipartFile pngFile() {
        return new MockMultipartFile(
                "file",
                "cover.png",
                "image/png",
                new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}
        );
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TestConfiguration {

        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL(
                    "jdbc:h2:mem:kuros-media-upload-transaction;MODE=MySQL;"
                            + "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
            );
            dataSource.setUser("sa");
            new JdbcTemplate(dataSource).execute("""
                    CREATE TABLE media_assets (
                        id VARCHAR(36) PRIMARY KEY,
                        owner_id VARCHAR(36) NOT NULL,
                        storage_key VARCHAR(255) NOT NULL,
                        original_name VARCHAR(255) NOT NULL,
                        content_type VARCHAR(64) NOT NULL,
                        size_bytes BIGINT NOT NULL,
                        status VARCHAR(32) NOT NULL,
                        created_at TIMESTAMP NOT NULL
                    )
                    """);
            return dataSource;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        MediaAssetRepository mediaAssetRepository(DataSource dataSource) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            return (MediaAssetRepository) Proxy.newProxyInstance(
                    MediaAssetRepository.class.getClassLoader(),
                    new Class<?>[]{MediaAssetRepository.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("hashCode")) {
                            return System.identityHashCode(proxy);
                        }
                        if (method.getName().equals("equals")) {
                            return proxy == args[0];
                        }
                        if (method.getName().equals("toString")) {
                            return "MediaAssetRepositoryJdbcProxy";
                        }
                        if (method.getName().equals("save")) {
                            MediaAsset asset = (MediaAsset) args[0];
                            jdbc.update("""
                                            INSERT INTO media_assets
                                                (id, owner_id, storage_key, original_name, content_type,
                                                 size_bytes, status, created_at)
                                            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                                            """,
                                    asset.getId(),
                                    asset.getOwnerId(),
                                    asset.getStorageKey(),
                                    asset.getOriginalName(),
                                    asset.getContentType(),
                                    asset.getSizeBytes(),
                                    asset.getStatus().name(),
                                    Timestamp.valueOf(asset.getCreatedAt())
                            );
                            return asset;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    }
            );
        }

        @Bean
        VisibilityStorage storageStrategy(DataSource dataSource) {
            return new VisibilityStorage(dataSource);
        }

        @Bean
        DefaultMediaAssetService mediaAssetService(
                VisibilityStorage storageStrategy,
                MediaAssetRepository mediaAssetRepository
        ) {
            return new DefaultMediaAssetService(storageStrategy, mediaAssetRepository);
        }
    }

    static final class VisibilityStorage implements StorageStrategy {

        private final DataSource dataSource;
        private final AtomicInteger visibleDuringPut = new AtomicInteger(-1);

        VisibilityStorage(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public void put(String key, byte[] bytes, String contentType) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                visibleDuringPut.set(executor.submit(() -> {
                    Integer count = new JdbcTemplate(dataSource).queryForObject(
                            "SELECT COUNT(*) FROM media_assets WHERE status = 'TEMPORARY'",
                            Integer.class
                    );
                    return count == null ? -1 : count;
                }).get(5, TimeUnit.SECONDS));
            } catch (Exception exception) {
                throw new IllegalStateException("无法从独立连接检查元数据可见性", exception);
            } finally {
                executor.shutdownNow();
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
