package com.kuros.kurosbackend.web;

import com.kuros.kurosbackend.api.ApiResponse;
import com.kuros.kurosbackend.api.PostInteractionResponse;
import com.kuros.kurosbackend.service.PostInteractionService;
import org.springframework.security.core.Authentication;
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
    public ApiResponse<PostInteractionResponse> find(
            @PathVariable String postId,
            Authentication authentication
    ) {
        return new ApiResponse<>(interactionService.findPost(postId, userId(authentication)), null);
    }

    @PostMapping("/like")
    public ApiResponse<PostInteractionResponse> like(@PathVariable String postId, Authentication authentication) {
        return new ApiResponse<>(interactionService.like(postId, authentication.getName()), null);
    }

    @DeleteMapping("/like")
    public ApiResponse<PostInteractionResponse> unlike(@PathVariable String postId, Authentication authentication) {
        return new ApiResponse<>(interactionService.unlike(postId, authentication.getName()), null);
    }

    @PostMapping("/favorite")
    public ApiResponse<PostInteractionResponse> favorite(@PathVariable String postId, Authentication authentication) {
        return new ApiResponse<>(interactionService.favorite(postId, authentication.getName()), null);
    }

    @DeleteMapping("/favorite")
    public ApiResponse<PostInteractionResponse> unfavorite(@PathVariable String postId, Authentication authentication) {
        return new ApiResponse<>(interactionService.unfavorite(postId, authentication.getName()), null);
    }

    private String userId(Authentication authentication) {
        return authentication == null ? null : authentication.getName();
    }
}
