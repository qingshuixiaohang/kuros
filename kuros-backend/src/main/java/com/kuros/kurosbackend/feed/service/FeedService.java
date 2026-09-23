package com.kuros.kurosbackend.feed.service;

import com.kuros.kurosbackend.feed.redis.FeedTimelineStore;
import com.kuros.kurosbackend.post.api.PostSummaryResponse;
import com.kuros.kurosbackend.post.service.CommunityPostService;
import com.kuros.kurosbackend.shared.api.CursorCodec;
import com.kuros.kurosbackend.shared.api.CursorPageResult;
import com.kuros.kurosbackend.shared.api.PageMeta;
import com.kuros.kurosbackend.shared.api.PageResult;
import com.kuros.kurosbackend.shared.exception.AuthRequestException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Feed 流查询服务（关注流）。
 *
 * 读路径：ZREVRANGE 取 timeline postId 列表 → 委托 CommunityPostService 构建摘要
 * （复用作者回填/媒体查询/标签提取等逻辑，避免分叉）。
 *
 * 为什么委托而不自己构造 PostSummaryResponse：
 * ① PostSummaryResponse 的构造涉及 PostMediaRepository/MediaAssetRepository（图片 URL 拼装）
 *    + UserDirectoryFacade.toAuthor（作者昵称 + 降级占位）+ 标签排序，逻辑不轻；
 * ② 未来改 summary 格式时只改一处（CommunityPostService.toSummary）。
 */
@Service
@Transactional(readOnly = true)
public class FeedService {

    private static final int MAX_PAGE_SIZE = 50;

    private final FeedTimelineStore timelineStore;
    private final CommunityPostService postService;

    public FeedService(
            FeedTimelineStore timelineStore,
            CommunityPostService postService
    ) {
        this.timelineStore = timelineStore;
        this.postService = postService;
    }

    /**
     * 关注流：按时间倒序返回当前用户关注的人发布的帖子。
     *
     * @param userId   当前登录用户 ID
     * @param page     页码（1-based）
     * @param pageSize 每页条数
     * @return 帖子列表 + 分页元数据
     */
    public PageResult<PostSummaryResponse> findFollowingFeed(String userId, int page, int pageSize) {
        int normalizedPage = Math.max(page, 1);
        int normalizedPageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        int offset = (normalizedPage - 1) * normalizedPageSize;

        // 1. 从 timeline ZSet 取 postId 列表（已按时间倒序）
        List<String> postIds = timelineStore.readTimeline(userId, offset, normalizedPageSize);
        long total = timelineStore.size(userId);

        if (postIds.isEmpty()) {
            return new PageResult<>(List.of(),
                    new PageMeta(normalizedPage, normalizedPageSize, total,
                            (int) Math.ceil((double) total / normalizedPageSize)));
        }

        // 2. 委托 CommunityPostService 构建摘要（批量查帖子 + 作者回填 + 媒体查询）
        //    findPublishedByIds 内部会过滤 null（已删除的帖子），但不过滤非 PUBLISHED 状态
        //    ——这里传的 postId 都来自 timeline（发帖时已 PUBLISHED），删除的帖子在 findAllById 查不到
        //    自然被 filter(Objects::nonNull) 跳过。
        Page<String> idPage = new PageImpl<>(postIds,
                PageRequest.of(normalizedPage - 1, normalizedPageSize), total);
        return postService.findPublishedByIds(idPage);
    }

    /**
     * 关注流游标翻页（切片 #13 / rp-04）：以上一页最后一条的 (score, postId) 为游标，取下一页。
     *
     * 为什么用游标而不是沿用 {@link #findFollowingFeed} 的 offset？
     * offset 翻页要 ZREVRANGE 跳过前 offset 个、且为算 totalPages 要取 timeline size；游标直接定位、不取总数，
     * 翻到多深都是 O(log N + limit)，契合无限滚动的“加载更多”（见 spec D6/D8）。
     *
     * hasMore 探测：多取一条（limit+1），实取到 &gt; limit 则说明还有下一页，截断回 limit 条。
     * nextCursor 基于「timeline 原始 id 序列」的最后一条（而非过滤已删后的可见项），
     * 保证翻页按 timeline 位置稳定推进，不因中间有已删帖而错位或丢页。
     *
     * @param cursor 不透明游标串（编码了 score + postId）；null/空表示第一页
     * @param limit  每页条数
     */
    public CursorPageResult<PostSummaryResponse> findFollowingFeedByCursor(String userId, String cursor, int limit) {
        int normalizedLimit = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
        FeedCursor feedCursor = decodeFeedCursor(cursor);

        // 多取一条用于探测是否还有下一页
        List<String> ids = timelineStore.readTimelineByCursor(
                userId,
                feedCursor == null ? null : feedCursor.score(),
                feedCursor == null ? null : feedCursor.postId(),
                normalizedLimit + 1);
        if (ids.isEmpty()) {
            return CursorPageResult.empty();
        }
        boolean hasMore = ids.size() > normalizedLimit;
        List<String> pageIds = hasMore ? ids.subList(0, normalizedLimit) : ids;

        String nextCursor = null;
        if (hasMore) {
            String lastId = pageIds.get(pageIds.size() - 1);
            long lastScore = timelineStore.scoreOf(userId, lastId);
            nextCursor = CursorCodec.encode(List.of(Long.toString(lastScore), lastId));
        }

        // 复用 findPublishedByIds 构建摘要（批量查帖 + 作者回填 + 媒体），保留 pageIds 顺序、自然过滤已物理删除的帖
        Page<String> idPage = new PageImpl<>(pageIds, PageRequest.of(0, pageIds.size()), pageIds.size());
        List<PostSummaryResponse> items = postService.findPublishedByIds(idPage).items();
        return new CursorPageResult<>(items, nextCursor, hasMore);
    }

    /** 关注流游标：(score, postId) 二元组，同分时靠 postId 字典序兜底全序。 */
    private record FeedCursor(long score, String postId) {
    }

    private FeedCursor decodeFeedCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null; // 第一页
        }
        List<String> tokens = CursorCodec.decode(cursor);
        if (tokens.size() != 2) {
            throw new AuthRequestException("INVALID_CURSOR", "关注流游标格式无效");
        }
        try {
            return new FeedCursor(Long.parseLong(tokens.get(0)), tokens.get(1));
        } catch (NumberFormatException e) {
            throw new AuthRequestException("INVALID_CURSOR", "关注流游标格式无效");
        }
    }
}
