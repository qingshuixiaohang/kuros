package com.kuros.kurosbackend.feed.web;

import cn.dev33.satoken.stp.StpUtil;
import com.kuros.kurosbackend.feed.service.FeedService;
import com.kuros.kurosbackend.post.api.PostSummaryResponse;
import com.kuros.kurosbackend.shared.api.ApiResponse;
import com.kuros.kurosbackend.shared.api.CursorPageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Feed 流端点（关注流）。
 *
 * GET /api/v1/feed/following?page=1&pageSize=20
 * - 需要登录（SaToken 拦截器保护，见 SaTokenConfig notMatch 白名单之外的路径默认拦截）
 * - 返回当前用户关注的人发布的帖子（按时间倒序，来自 Redis ZSet timeline）
 * - 未关注任何人时返回空列表（items=[]），meta.totalItems=0
 *
 * 推荐流和最新流不在此控制器——它们复用现有 GET /api/v1/posts?sort=hot|latest。
 * 前端三 tab 切换时：
 * - "推荐" → GET /api/v1/posts?sort=hot
 * - "最新" → GET /api/v1/posts?sort=latest
 * - "关注" → GET /api/v1/feed/following
 */
@RestController
@RequestMapping("/api/v1/feed")
public class FeedController {

    private final FeedService feedService;

    public FeedController(FeedService feedService) {
        this.feedService = feedService;
    }

    @GetMapping("/following")
    public ApiResponse<?> following(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize
    ) {
        String userId = StpUtil.getLoginIdAsString();
        // 以是否传 limit 区分模式（spec D9）：传了 limit → 游标无限滚动（cursor 空表示第一页）；
        // 否则走旧 offset 分页，两套契约并存不破坏现有调用方
        if (limit != null) {
            CursorPageResult<PostSummaryResponse> result =
                    feedService.findFollowingFeedByCursor(userId, cursor, limit);
            // 游标模式无总数，meta 为 null（ApiResponse 标了 @JsonInclude(NON_NULL)，不会序列化出去）
            return new ApiResponse<>(result, null);
        }
        var result = feedService.findFollowingFeed(userId, page, pageSize);
        return new ApiResponse<>(result.items(), result.meta());
    }
}
