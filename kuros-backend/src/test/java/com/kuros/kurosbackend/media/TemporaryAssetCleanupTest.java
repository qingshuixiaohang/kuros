package com.kuros.kurosbackend.media;

import com.kuros.kurosbackend.media.domain.MediaAsset;
import com.kuros.kurosbackend.media.repository.MediaAssetRepository;
import com.kuros.kurosbackend.media.storage.DefaultMediaAssetService;
import com.kuros.kurosbackend.media.storage.StorageStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TemporaryAssetCleanupTest {

    private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 10, 13, 12, 0);
    @Mock private MediaAssetRepository mediaAssetRepository;
    @Mock private StorageStrategy storageStrategy;
    private DefaultMediaAssetService service;

    @BeforeEach
    void setUp() {
        service = new DefaultMediaAssetService(storageStrategy, mediaAssetRepository);
    }

    @Test
    void 单个文件删除失败不影响同批其余文件() {
        List<MediaAsset> assets = candidates(3);
        doThrow(new RuntimeException("存储暂时不可用")).when(storageStrategy).delete("orphan-1.png");
        doNothing().when(storageStrategy).delete("orphan-0.png");
        doNothing().when(storageStrategy).delete("orphan-2.png");
        assertThat(service.cleanupTemporaryAssets(CUTOFF)).isEqualTo(2);
        verify(storageStrategy, times(3)).delete(anyString());
        verify(mediaAssetRepository).recordStorageDeletion(eq(assets.get(0).getId()), any());
        verify(mediaAssetRepository, never()).recordStorageDeletion(eq(assets.get(1).getId()), any());
        verify(mediaAssetRepository).recordStorageDeletion(eq(assets.get(2).getId()), any());
    }

    @Test
    void 全部失败时返回零且下一轮可以重试() {
        candidates(3);
        doThrow(new RuntimeException("存储暂时不可用")).when(storageStrategy).delete(anyString());
        assertThat(service.cleanupTemporaryAssets(CUTOFF)).isZero();
        verify(mediaAssetRepository, never()).recordStorageDeletion(anyString(), any());
        doNothing().when(storageStrategy).delete(anyString());
        assertThat(service.cleanupTemporaryAssets(CUTOFF)).isEqualTo(3);
    }

    @Test
    void 无过期文件时不调用存储层() {
        when(mediaAssetRepository.findCleanupCandidates(eq(CUTOFF), eq(""), any())).thenReturn(List.of());
        assertThat(service.cleanupTemporaryAssets(CUTOFF)).isZero();
        verifyNoInteractions(storageStrategy);
    }

    @Test
    void 发布已抢先关联时不删除对象() {
        List<MediaAsset> assets = candidates(1);
        when(mediaAssetRepository.claimCleanup(assets.get(0).getId(), CUTOFF)).thenReturn(0);
        assertThat(service.cleanupTemporaryAssets(CUTOFF)).isZero();
        verifyNoInteractions(storageStrategy);
    }

    @Test
    void 全部成功时全部清理() {
        candidates(3);
        assertThat(service.cleanupTemporaryAssets(CUTOFF)).isEqualTo(3);
        verify(storageStrategy, times(3)).delete(anyString());
    }

    @Test
    void 清理前修复仍被已发布帖子引用的删除资产() {
        when(mediaAssetRepository.repairDeletedAssetsWithPublishedReferences()).thenReturn(1);
        when(mediaAssetRepository.findCleanupCandidates(eq(CUTOFF), eq(""), any())).thenReturn(List.of());

        assertThat(service.cleanupTemporaryAssets(CUTOFF)).isZero();

        verify(mediaAssetRepository).repairDeletedAssetsWithPublishedReferences();
        verifyNoInteractions(storageStrategy);
    }

    private List<MediaAsset> candidates(int count) {
        List<MediaAsset> assets = IntStream.range(0, count).mapToObj(index -> MediaAsset.temporary(
                "owner-1", "orphan-" + index + ".png", "image.png", "image/png", 8,
                CUTOFF.minusDays(1))).toList();
        when(mediaAssetRepository.findCleanupCandidates(eq(CUTOFF), eq(""), any())).thenReturn(assets);
        lenient().when(mediaAssetRepository.claimCleanup(anyString(), eq(CUTOFF))).thenReturn(1);
        return assets;
    }
}
