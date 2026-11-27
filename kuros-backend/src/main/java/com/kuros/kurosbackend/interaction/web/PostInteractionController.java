package com.kuros.kurosbackend.interaction.web;

import cn.dev33.satoken.stp.StpUtil;
import com.kuros.kurosbackend.shared.api.ApiResponse;
import com.kuros.kurosbackend.interaction.api.PostInteractionResponse;
import com.kuros.kurosbackend.interaction.service.PostInteractionService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.http.ResponseEntity;
import com.kuros.kurosbackend.interaction.event.InteractionType;
import java.util.UUID;

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
    public ResponseEntity<?> like(@PathVariable String postId, @RequestHeader(value = "Idempotency-Key", required = false) UUID operationId) {
        if (operationId != null) return submit(postId, operationId, InteractionType.LIKE);
        return ResponseEntity.ok(new ApiResponse<>(interactionService.like(postId, StpUtil.getLoginIdAsString()), null));
    }

    @DeleteMapping("/like")
    public ResponseEntity<?> unlike(@PathVariable String postId, @RequestHeader(value = "Idempotency-Key", required = false) UUID operationId) {
        if (operationId != null) return submit(postId, operationId, InteractionType.UNLIKE);
        return ResponseEntity.ok(new ApiResponse<>(interactionService.unlike(postId, StpUtil.getLoginIdAsString()), null));
    }

    @PostMapping("/favorite")
    public ResponseEntity<?> favorite(@PathVariable String postId, @RequestHeader(value = "Idempotency-Key", required = false) UUID operationId) {
        if (operationId != null) return submit(postId, operationId, InteractionType.FAVORITE);
        return ResponseEntity.ok(new ApiResponse<>(interactionService.favorite(postId, StpUtil.getLoginIdAsString()), null));
    }

    @DeleteMapping("/favorite")
    public ResponseEntity<?> unfavorite(@PathVariable String postId, @RequestHeader(value = "Idempotency-Key", required = false) UUID operationId) {
        if (operationId != null) return submit(postId, operationId, InteractionType.UNFAVORITE);
        return ResponseEntity.ok(new ApiResponse<>(interactionService.unfavorite(postId, StpUtil.getLoginIdAsString()), null));
    }

    private ResponseEntity<?> submit(String postId, UUID operationId, InteractionType type) {
        interactionService.submit(postId, StpUtil.getLoginIdAsString(), operationId.toString(), type);
        return ResponseEntity.accepted().body(new ApiResponse<>(new InteractionOperationController.Accepted(operationId), null));
    }
}
