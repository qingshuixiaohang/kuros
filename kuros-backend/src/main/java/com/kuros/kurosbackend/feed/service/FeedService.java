package com.kuros.kurosbackend.feed.service;

import com.kuros.kurosbackend.feed.redis.FeedTimelineStore;
import com.kuros.kurosbackend.post.api.PostSummaryResponse;
import com.kuros.kurosbackend.post.service.CommunityPostService;
import com.kuros.kurosbackend.shared.api.PageMeta;
import com.kuros.kurosbackend.shared.api.PageResult;
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
}
