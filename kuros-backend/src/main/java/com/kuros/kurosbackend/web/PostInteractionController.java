package com.kuros.kurosbackend.web;

import cn.dev33.satoken.stp.StpUtil;
import com.kuros.kurosbackend.api.ApiResponse;
import com.kuros.kurosbackend.api.PostInteractionResponse;
import com.kuros.kurosbackend.service.PostInteractionService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/posts/{postId}/interactions")
public class PostInteractionController {

    private final PostInteractionService interactionService;

    public PostInteractionController(PostInteractionService interactionService) {
        this.interactionService = interactionService;
    }

    @GetMapping
    public ApiResponse<PostInteractionResponse> find(@PathVariable String postId) {
        // 公开接口：未登录时返回默认空状态，已登录时返回实际互动状态
        String userId = StpUtil.isLogin() ? StpUtil.getLoginIdAsString() : null;
        return new ApiResponse<>(interactionService.findPost(postId, userId), null);
    }

    @PostMapping("/like")
    public ApiResponse<PostInteractionResponse> like(@PathVariable String postId) {
        return new ApiResponse<>(interactionService.like(postId, StpUtil.getLoginIdAsString()), null);
    }

    @DeleteMapping("/like")
    public ApiResponse<PostInteractionResponse> unlike(@PathVariable String postId) {
        return new ApiResponse<>(interactionService.unlike(postId, StpUtil.getLoginIdAsString()), null);
    }

    @PostMapping("/favorite")
    public ApiResponse<PostInteractionResponse> favorite(@PathVariable String postId) {
        return new ApiResponse<>(interactionService.favorite(postId, StpUtil.getLoginIdAsString()), null);
    }

    @DeleteMapping("/favorite")
    public ApiResponse<PostInteractionResponse> unfavorite(@PathVariable String postId) {
        return new ApiResponse<>(interactionService.unfavorite(postId, StpUtil.getLoginIdAsString()), null);
    }
}
