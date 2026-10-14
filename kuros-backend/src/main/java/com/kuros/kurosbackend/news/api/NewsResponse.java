package com.kuros.kurosbackend.news.api;

import java.time.LocalDate;
import java.util.List;

/**
 * 资讯响应体。
 *
 * 与前端 mock 的 NewsItem 对齐（id/rank/title/hot/category/date/summary/content），
 * 但有两处刻意不同：
 * - 对外用 slug 而不是 UUID——URL 可读，且不暴露内部主键
 * - date 用 ISO 字符串（LocalDate 默认序列化即 ISO），前端自己格式化；
 *   后端不返回"2025 / 09 / 13"这种展示格式，那是视图层的事
 */
public record NewsResponse(
        String id,
        String slug,
        String title,
        String category,
        String summary,
        LocalDate date,
        boolean hot,
        List<String> content
) {
}
