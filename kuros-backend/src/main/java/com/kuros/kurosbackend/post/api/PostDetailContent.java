package com.kuros.kurosbackend.post.api;

import com.kuros.kurosbackend.post.domain.PostType;
import com.kuros.kurosbackend.shared.api.AuthorResponse;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 帖子详情的「内容字段」缓存载体（切片 #13 / ADR 0006 D3：缓存与实时计数解耦）。
 *
 * 为什么不像 {@link PostDetailResponse} 那样带上 likeCount / favoriteCount？
 * 因为 #11 之后点赞/收藏的权威实时源是 Redis（InteractionRedisStore），而 DB 冗余列是异步落库的、
 * 有滞后。若把含计数的详情整个缓存，缓存里的计数会与实时源打架、且过期计数要等缓存失效才刷新——
 * 热帖越热，计数越不准。解耦后：内容字段（几乎不变）进两级缓存，计数在「组装响应那一刻」从实时源叠加，
 * 缓存永不因计数变化而失效，驱逐只由内容变更（编辑/删除）触发。
 *
 * 注意 viewCount / commentCount 仍是 DB 列、随内容一起缓存：
 * - 它们不在 #11 的实时互动源（仅 like/favorite）覆盖范围内；
 * - 二者变化频率低、且非「热点竞争」维度，缓存到 TTL 过期刷新即可接受（commentCount 的实时化是独立缺陷工单）。
 *
 * 该 record 会被 Jackson 序列化为 L2（Redis）里的 JSON，字段增减即缓存结构演进——
 * 旧条目反序列化失败会被 TwoLevelCache 降级为 miss 重建，无需手动清缓存。
 */
public record PostDetailContent(
        String id,
        PostType type,
        String category,
        String title,
        String excerpt,
        String content,
        AuthorResponse author,
        LocalDateTime publishedAt,
        long viewCount,
        long commentCount,
        List<String> tags,
        String coverImageUrl,
        List<PostMediaResponse> media
) {
}
