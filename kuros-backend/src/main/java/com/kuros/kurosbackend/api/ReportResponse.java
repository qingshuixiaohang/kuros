package com.kuros.kurosbackend.api;

import com.kuros.kurosbackend.domain.ReportReason;
import com.kuros.kurosbackend.domain.ReportStatus;
import com.kuros.kurosbackend.domain.ReportTargetType;

import java.time.LocalDateTime;

public record ReportResponse(
        String id,
        String reporterId,
        ReportTargetType targetType,
        String targetId,
        ReportReason reason,
        ReportStatus status,
        String handledBy,
        LocalDateTime handledAt,
        String handlingNote,
        LocalDateTime createdAt
) {
}
