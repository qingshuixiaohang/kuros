package com.kuros.kurosbackend.media;

import com.kuros.kurosbackend.media.domain.MediaAsset;
import com.kuros.kurosbackend.media.domain.MediaAssetStatus;
import com.kuros.kurosbackend.media.repository.MediaAssetRepository;
import com.kuros.kurosbackend.media.storage.DefaultMediaAssetService;
import com.kuros.kurosbackend.media.storage.StorageStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 临时图片清理的失败语义（可靠性加固）。
 *
 * 要锁定的契约：清理是**逐项尽力而为**——一个文件的存储删除失败，不能
 * 带走整批剩余文件的处理，也不能让已删文件的 DB 状态停在未删除。
 *
 * 原实现的三个问题（见 DefaultMediaAssetService.cleanupTemporaryAssets）：
 * 1. 单点失败中断整批：N 个过期文件删到第 k 个时存储抖动抛异常，后
 *    N-k 个这次不处理；下次重跑还会在同一个位置崩——清理永远跑不完。
 * 2. saveAll 在循环外：中途抛异常时，前 k-1 个「物理文件已删但 DB 状态
 *    未改」与后 N-k 个「完全没处理」两种状态混在一起，且无法区分。
 * 3. 失败文件的处理方式不明：若在 delete 失败后仍 markDeleted，物理文件
 *    还在但 DB 说已删 → 永久孤儿文件，永远不会再被清理。
 *
 * 修复后的契约：
 * - 每个文件独立 try/catch：单个失败只跳过它，继续处理其余
 * - delete 失败的文件**保持 TEMPORARY**（不 markDeleted），等下次重试
 * - 返回值 = 实际成功处理的文件数；全部失败返回 0 而不抛异常——
 *   清理是 best-effort 的后台任务，存储整体不可用不该让定时任务框架
 *   判定作业本身失败
 *
 * 测试选型：纯 Mockito 单元测试而非 @SpringBootTest。被测方法只依赖
 * repository 与 storageStrategy 两个协作者，都不需要 Spring 装配；
 * 且 @SpringBootTest 下替换 bean 需要 bean 替换手段（Spring Boot 4 已
 * 移除 @MockitoBean），手传 mock 更直接。~1s vs ~17s。
 */
@ExtendWith(MockitoExtension.class)
class TemporaryAssetCleanupTest {

    private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 10, 13, 12, 0);

    @Mock
    private MediaAssetRepository mediaAssetRepository;

    @Mock
    private StorageStrategy storageStrategy;

    private DefaultMediaAssetService service;

    @BeforeEach
    void setUp() {
        service = new DefaultMediaAssetService(storageStrategy, mediaAssetRepository);
    }

    /**
     * 造 count 个已过期的 TEMPORARY 资产。
     *
     * 用真实工厂方法 MediaAsset.temporary 而不是 builder/直接 set 字段——
     * 域对象的不变式（UUID 生成、初始状态、时间戳）应当由生产代码自己建立，
     * 测试绕过它会造出生产不可能产生的状态。
     */
    private List<MediaAsset> expiredAssets(int count) {
        List<MediaAsset> assets = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            assets.add(MediaAsset.temporary(
                    "owner-1",
                    "orphan-" + i + ".png",
                    "orphan-" + i + ".png",
                    "image/png",
                    1024L,
                    CUTOFF.minusHours(2)));
        }
        return assets;
    }

    /**
     * 核心契约：第 2 个文件删除失败时，第 1、3 个仍被处理完。
     *
     * 这是原实现最严重的缺陷——单点失败带走整批剩余文件，且下次重跑
     * 还会崩在同一位置，清理永远跑不完。
     */
    @Test
    void 单个文件删除失败不影响同批其余文件() {
        List<MediaAsset> assets = expiredAssets(3);
        when(mediaAssetRepository.findByStatusAndCreatedAtBefore(MediaAssetStatus.TEMPORARY, CUTOFF))
                .thenReturn(assets);

        // 只让第 2 个失败（按 storageKey 精确匹配），其余走默认空行为。
        // 必须显式给默认行为：Mockito 严格模式会把"未被 stub 的调用"当
        // 作 stub 写错而报 PotentialStubbingProblem，即使那正是我们要的
        // "其他文件正常删除"语义。
        doThrow(new RuntimeException("存储暂时不可用"))
                .when(storageStrategy).delete("orphan-1.png");
        org.mockito.Mockito.doNothing().when(storageStrategy).delete("orphan-0.png");
        org.mockito.Mockito.doNothing().when(storageStrategy).delete("orphan-2.png");

        int cleaned = service.cleanupTemporaryAssets(CUTOFF);

        // 成功的 2 个被清理
        assertThat(cleaned)
                .as("单个失败不应带走其余文件：3 个里成功 2 个")
                .isEqualTo(2);

        // 三个都尝试过删除（没有被第一个失败中断）
        verify(storageStrategy, times(3)).delete(anyString());

        // 成功的两个状态是 DELETED
        assertThat(assets.get(0).getStatus()).isEqualTo(MediaAssetStatus.DELETED);
        assertThat(assets.get(2).getStatus()).isEqualTo(MediaAssetStatus.DELETED);

        // 失败的那个保持 TEMPORARY，等下次重试（不能变成 DELETED，
        // 否则物理文件还在但 DB 说已删，成为永久孤儿）
        assertThat(assets.get(1).getStatus())
                .as("删除失败的文件必须保持 TEMPORARY 以便下次重试")
                .isEqualTo(MediaAssetStatus.TEMPORARY);

        // 只保存成功的那些
        verify(mediaAssetRepository).saveAll(List.of(assets.get(0), assets.get(2)));
    }

    /**
     * 全部失败时返回 0 而不抛异常：清理是 best-effort 的后台任务，
     * 存储整体不可用时不该让定时任务框架判定作业失败。
     */
    @Test
    void 全部失败时返回零而不抛异常() {
        List<MediaAsset> assets = expiredAssets(3);
        when(mediaAssetRepository.findByStatusAndCreatedAtBefore(MediaAssetStatus.TEMPORARY, CUTOFF))
                .thenReturn(assets);

        // 全部失败：用 anyString 覆盖所有 delete 调用
        doThrow(new RuntimeException("MinIO 完全不可达"))
                .when(storageStrategy).delete(anyString());

        int cleaned = service.cleanupTemporaryAssets(CUTOFF);

        assertThat(cleaned).isZero();
        // 三个都保持 TEMPORARY，等下次重试
        assertThat(assets)
                .extracting(MediaAsset::getStatus)
                .containsOnly(MediaAssetStatus.TEMPORARY);
        // 没有可保存的（没有一个成功），不该调 saveAll
        verify(mediaAssetRepository, never()).saveAll(any());
    }

    /**
     * 无过期文件时不触碰存储层与保存。
     */
    @Test
    void 无过期文件时不调用存储层() {
        when(mediaAssetRepository.findByStatusAndCreatedAtBefore(MediaAssetStatus.TEMPORARY, CUTOFF))
                .thenReturn(List.of());

        int cleaned = service.cleanupTemporaryAssets(CUTOFF);

        assertThat(cleaned).isZero();
        verify(storageStrategy, never()).delete(anyString());
        verify(mediaAssetRepository, never()).saveAll(any());
    }

    /**
     * 成功路径仍成立（防止修失败语义时弄坏正常路径）。
     */
    @Test
    void 全部成功时全部清理() {
        List<MediaAsset> assets = expiredAssets(3);
        when(mediaAssetRepository.findByStatusAndCreatedAtBefore(MediaAssetStatus.TEMPORARY, CUTOFF))
                .thenReturn(assets);

        int cleaned = service.cleanupTemporaryAssets(CUTOFF);

        assertThat(cleaned).isEqualTo(3);
        verify(storageStrategy, times(3)).delete(anyString());
        assertThat(assets)
                .extracting(MediaAsset::getStatus)
                .containsOnly(MediaAssetStatus.DELETED);
    }
}
