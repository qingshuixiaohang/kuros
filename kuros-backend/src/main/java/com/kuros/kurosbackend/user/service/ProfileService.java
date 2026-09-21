package com.kuros.kurosbackend.user.service;

import com.kuros.kurosbackend.shared.api.PageResult;
import com.kuros.kurosbackend.post.api.PostSummaryResponse;
import com.kuros.kurosbackend.user.api.ProfileOverviewResponse;
import com.kuros.kurosbackend.user.api.PublicProfileResponse;
import com.kuros.kurosbackend.shared.exception.ServiceUnavailableException;
import com.kuros.kurosbackend.post.service.CommunityPostService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户资料服务（split-07 后进入"窗口期降级"状态）。
 *
 * 背景：split-07 把用户域数据整体迁往 kuros-user（V10 从本库 DROP users 等表），
 * 本服务已无法在本库读出用户资料与关注关系，因此：
 * - findPublic / findOwn：直接 503（语义与恢复路径见 ServiceUnavailableException 注释）
 * - findPublicPosts：保持可用——帖子数据仍在本库，按作者查帖不依赖用户表
 *   （作者显示为占位作者，authorId 保留，见 CommunityPostService.toAuthor）
 *
 * 为什么保留这些 503 方法的签名而不是随缓存一起删掉：
 * 端点契约（路径/方法/返回结构）由 ProfileController 保持不变，
 * split-08 用 Feign 回填实现时只改方法体，控制器与前端零改动。
 * findOwn 的 userId/page/pageSize 参数在窗口期未使用，同样为 split-08 保留。
 */
@Service
@Transactional(readOnly = true)
public class ProfileService {

    private final CommunityPostService postService;

    public ProfileService(CommunityPostService postService) {
        this.postService = postService;
    }

    /**
     * 公开资料：GET /api/v1/users/{userId}（资料页头部）。
     * split-08 起改经 Feign 读 kuros-user 的内部 API（批量用户摘要）。
     */
    public PublicProfileResponse findPublic(String userId) {
        throw new ServiceUnavailableException("用户资料暂不可用");
    }

    /**
     * 按作者查已发布帖子：GET /api/v1/users/{userId}/posts。
     * 帖子本库自持，无需用户表；不再前置校验用户存在性——
     * 未知作者 ID 自然命中空列表（meta.totalItems=0），对调用方语义不退化。
     */
    public PageResult<PostSummaryResponse> findPublicPosts(String userId, int page, int pageSize) {
        return postService.findPublishedByAuthor(userId, page, pageSize);
    }

    /**
     * 个人中心聚合：GET /api/v1/users/me/profile。
     * 聚合依赖用户资料 + 帖子/评论/收藏/关注，其中用户资料与关注关系已迁出本库，
     * 窗口期整端降级（而不是部分降级——半截数据比明确的 503 更难被前端消费）；
     * split-08 经 Feign 回填后恢复完整聚合。
     */
    public ProfileOverviewResponse findOwn(String userId, int page, int pageSize) {
        throw new ServiceUnavailableException("个人中心暂不可用");
    }
}
