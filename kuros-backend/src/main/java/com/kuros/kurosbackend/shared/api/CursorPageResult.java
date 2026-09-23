package com.kuros.kurosbackend.shared.api;

import java.util.List;

/**
 * 游标分页结果契约（切片 #13 / rp-04 / ADR 0006 D8）。
 *
 * 为什么不用现有的 {@link PageResult}（offset + PageMeta）？
 * offset 分页必须执行 {@code COUNT(*)} 求 totalItems/totalPages，深翻页时既要扫弃前 offset 行、又要全表计数，
 * 是 O(offset+n) 的退化根源。游标分页只回答「下一页从哪开始、还有没有」，天然不需要总数：
 * - {@code items}：本页数据（已按排序键有序）；
 * - {@code nextCursor}：下一页游标（不透明串，由排序键 + id 编码），{@code hasMore=false} 时为 null；
 * - {@code hasMore}：是否还有下一页——由「多取一条」探测得出，不查总数。
 * 前端只需把 nextCursor 原样透传回来即可无限翻页，翻到多深都是 O(limit)。
 */
public record CursorPageResult<T>(List<T> items, String nextCursor, boolean hasMore) {

    /** 空页便捷构造（无数据、无下一页）。 */
    public static <T> CursorPageResult<T> empty() {
        return new CursorPageResult<>(List.of(), null, false);
    }
}
