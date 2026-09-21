package com.kuros.kurosuser.web;

import cn.dev33.satoken.stp.StpUtil;
import com.kuros.kurosuser.api.UserFollowResponse;
import com.kuros.kurosuser.service.UserFollowService;
import com.kuros.kurosuser.shared.api.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 关注端点（split-07 自 kuros-backend 迁入，路径与响应逐字不变）。
 *
 * 迁移动机：关注关系的权威归属整体搬到用户服务——前端经网关的
 * {@code /api/v1/users/{targetUserId}/follow}（gateway 的 kuros-user-follow 路由）全部路由到本服务；
 * backend 侧同路径已无 handler（直连 404）。
 *
 * 匿名语义保持迁移前行为：GET 允许携带空会话（controller 内 isLogin 判断），
 * 但 SaToken 白名单并未为两段路径开匿名口子——实际入口由网关+前端控制
 * （前端只在 loggedIn 时查询）；POST/DELETE 一律要求登录（StpUtil 抛 NotLogin → 401）。
 */
@RestController
@RequestMapping("/api/v1/users/{targetUserId}/follow")
public class UserFollowController {

    private final UserFollowService followService;

    public UserFollowController(UserFollowService followService) {
        this.followService = followService;
    }

    @GetMapping
    public ApiResponse<UserFollowResponse> find(@PathVariable String targetUserId) {
        String userId = StpUtil.isLogin() ? StpUtil.getLoginIdAsString() : null;
        return new ApiResponse<>(followService.find(targetUserId, userId), null);
    }

    @PostMapping
    public ApiResponse<UserFollowResponse> follow(@PathVariable String targetUserId) {
        return new ApiResponse<>(followService.follow(targetUserId, StpUtil.getLoginIdAsString()), null);
    }

    @DeleteMapping
    public ApiResponse<UserFollowResponse> unfollow(@PathVariable String targetUserId) {
        return new ApiResponse<>(followService.unfollow(targetUserId, StpUtil.getLoginIdAsString()), null);
    }
}
