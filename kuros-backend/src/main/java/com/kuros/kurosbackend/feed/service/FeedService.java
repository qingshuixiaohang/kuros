package com.kuros.kurosbackend.feed.service;

import com.kuros.kurosbackend.feed.redis.FeedTimelineStore;
import com.kuros.kurosbackend.feed.redis.FeedTimelineStore.TimelineEntry;
import com.kuros.kurosbackend.post.api.PostSummaryResponse;
import com.kuros.kurosbackend.post.service.CommunityPostService;
import com.kuros.kurosbackend.post.domain.PostStatus;
import com.kuros.kurosbackend.post.repository.CommunityPostRepository;
import com.kuros.kurosbackend.user.client.UserDirectoryFacade;
import com.kuros.kurosbackend.user.client.UserBriefDto;
import com.kuros.kurosbackend.shared.api.CursorCodec;
import com.kuros.kurosbackend.shared.api.CursorPageResult;
import com.kuros.kurosbackend.shared.api.PageMeta;
import com.kuros.kurosbackend.shared.api.PageResult;
import com.kuros.kurosbackend.shared.exception.AuthRequestException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

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
    private final CommunityPostRepository posts;
    private final UserDirectoryFacade users;

    public FeedService(
            FeedTimelineStore timelineStore,
            CommunityPostService postService,
            CommunityPostRepository posts,
            UserDirectoryFacade users
    ) {
        this.timelineStore = timelineStore;
        this.postService = postService;
        this.posts = posts;
        this.users = users;
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
        Set<String> following = reconcileFollowing(userId);
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

        // 2. 委托 CommunityPostService 构建摘要（批量查帖子 + 作者回填 + 媒体查询）。
        //    findPublishedByIds 按 PUBLISHED 状态过滤：timeline ZSet 里的 postId
        //    不随软删除消失（删帖只改 status），只靠"查不到"是漏不掉删帖的
        //    （审计 M9 / D2 修的就是这个）。
        Page<String> idPage = new PageImpl<>(postIds,
                PageRequest.of(normalizedPage - 1, normalizedPageSize), total);
        var result = postService.findPublishedByIds(idPage);
        return new PageResult<>(result.items().stream().filter(item -> following.contains(item.author().id())).toList(), result.meta());
    }

    /**
     * 关注流游标翻页（切片 #13 / rp-04）：以上一页最后一条的 (score, Redis member) 为游标，取下一页。
     *
     * 为什么用游标而不是沿用 {@link #findFollowingFeed} 的 offset？
     * offset 翻页要 ZREVRANGE 跳过前 offset 个、且为算 totalPages 要取 timeline size；游标直接定位、不取总数，
     * 翻到多深都是 O(log N + limit)，契合无限滚动的“加载更多”（见 spec D6/D8）。
     *
     * hasMore 探测：多取一条（limit+1），实取到 &gt; limit 则说明还有下一页，截断回 limit 条。
     * nextCursor 基于「timeline 原始 id 序列」的最后一条（而非过滤已删后的可见项），
     * 保证翻页按 timeline 位置稳定推进，不因中间有已删帖而错位或丢页。
     *
     * @param cursor 不透明游标串（编码了 score + Redis member）；null/空表示第一页
     * @param limit  每页条数
     */
    public CursorPageResult<PostSummaryResponse> findFollowingFeedByCursor(String userId, String cursor, int limit) {
        int normalizedLimit = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
        FeedCursor feedCursor = decodeFeedCursor(cursor);
        Set<String> following = reconcileFollowing(userId);

        // 多取一条用于探测是否还有下一页
        List<TimelineEntry> entries = timelineStore.readTimelineByCursor(
                userId,
                feedCursor == null ? null : feedCursor.score(),
                feedCursor == null ? null : feedCursor.member(),
                normalizedLimit + 1);
        if (entries.isEmpty()) {
            return CursorPageResult.empty();
        }
        boolean hasMore = entries.size() > normalizedLimit;
        List<TimelineEntry> pageEntries = hasMore ? entries.subList(0, normalizedLimit) : entries;

        // nextCursor 直接用本次查询带回的 score——不必再发一次 ZSCORE
        // （member 已是复合键，调用方手里只有 postId，拼不出原始 member）。
        // 基于「timeline 原始 id 序列」的最后一条（而非过滤已删后的可见项），
        // 保证翻页按 timeline 位置稳定推进，不因中间有已删帖而错位或丢页。
        String nextCursor = null;
        if (hasMore) {
            TimelineEntry last = pageEntries.get(pageEntries.size() - 1);
            nextCursor = CursorCodec.encode(List.of(Long.toString(last.score()), last.member()));
        }

        List<String> pageIds = pageEntries.stream().map(TimelineEntry::postId).toList();
        Page<String> idPage = new PageImpl<>(pageIds, PageRequest.of(0, pageIds.size()), pageIds.size());
        List<PostSummaryResponse> items = postService.findPublishedByIds(idPage).items().stream()
                .filter(item -> following.contains(item.author().id())).toList();
        return new CursorPageResult<>(items, nextCursor, hasMore);
    }

    private Set<String> reconcileFollowing(String userId) {
        Set<String> following = new HashSet<>();
        int page = 1;
        PageResult<UserBriefDto> result;
        do {
            result = users.following(userId, page++, MAX_PAGE_SIZE);
            result.items().stream().map(UserBriefDto::id).forEach(following::add);
        } while (page <= result.meta().totalPages());

        String snapshot = String.join(",", following.stream().sorted().toList());
        if (!timelineStore.hasFollowingSnapshot(userId, snapshot)) {
            timelineStore.retainAuthors(userId, following);
            if (!following.isEmpty()) {
                var recent = posts.findByAuthorIdInAndStatus(following, PostStatus.PUBLISHED,
                        PageRequest.of(0, timelineStore.capacity(),
                                Sort.by(Sort.Order.desc("publishedAt"), Sort.Order.desc("id"))));
                timelineStore.backfill(userId, recent.stream()
                        .map(post -> new FeedTimelineStore.BackfillPost(post.getId(), post.getAuthorId(), post.getPublishedAt())).toList());
            }
            timelineStore.saveFollowingSnapshot(userId, snapshot);
        }
        return following;
    }

    /** 关注流游标：(score, Redis member) 二元组，同分时靠完整 member 字典序兜底。 */
    private record FeedCursor(long score, String member) {
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
