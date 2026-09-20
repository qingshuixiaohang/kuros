package com.kuros.kurosbackend.web;

import cn.dev33.satoken.stp.StpUtil;
import com.kuros.kurosbackend.api.ApiResponse;
import com.kuros.kurosbackend.api.UserFollowResponse;
import com.kuros.kurosbackend.service.UserFollowService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
