package com.kuros.kurosbackend.interaction.web;

import cn.dev33.satoken.stp.StpUtil;
import com.kuros.kurosbackend.interaction.service.InteractionProjectionService;
import com.kuros.kurosbackend.shared.api.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/interaction-failures")
public class InteractionFailureController {
    private final InteractionProjectionService projection;

    public InteractionFailureController(InteractionProjectionService projection) {
        this.projection = projection;
    }

    @GetMapping
    public ApiResponse<List<InteractionProjectionService.Failure>> list() {
        StpUtil.checkRole("ADMIN");
        return new ApiResponse<>(projection.pendingFailures(), null);
    }

    @PostMapping("/{operationId}/retry")
    public ApiResponse<Void> retry(@PathVariable UUID operationId) {
        StpUtil.checkRole("ADMIN");
        projection.resumeFailure(operationId.toString());
        return new ApiResponse<>(null, null);
    }
}
