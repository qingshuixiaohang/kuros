package com.kuros.kurosbackend.interaction.web;

import cn.dev33.satoken.stp.StpUtil;
import com.kuros.kurosbackend.interaction.event.InteractionType;
import com.kuros.kurosbackend.interaction.service.InteractionProjectionService;
import com.kuros.kurosbackend.interaction.service.PostInteractionService;
import com.kuros.kurosbackend.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/interaction-operations")
public class InteractionOperationController {
    private final PostInteractionService interactions;
    private final InteractionProjectionService projections;

    public InteractionOperationController(PostInteractionService interactions, InteractionProjectionService projections) {
        this.interactions = interactions;
        this.projections = projections;
    }

    public record Request(@NotNull UUID operationId, @NotNull UUID postId, @NotNull InteractionType type) { }
    public record Accepted(UUID operationId) { }

    @PostMapping
    public ResponseEntity<ApiResponse<Accepted>> submit(@Valid @RequestBody Request request) {
        interactions.submit(request.postId().toString(), StpUtil.getLoginIdAsString(),
                request.operationId().toString(), request.type());
        return ResponseEntity.accepted().body(new ApiResponse<>(new Accepted(request.operationId()), null));
    }

    @GetMapping("/{operationId}")
    public ApiResponse<InteractionProjectionService.OperationResult> find(@PathVariable UUID operationId) {
        return new ApiResponse<>(projections.findOperation(operationId.toString(), StpUtil.getLoginIdAsString()), null);
    }
}
