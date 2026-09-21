package com.kuros.kurosbackend.report.api;

import com.kuros.kurosbackend.report.domain.ReportReason;
import com.kuros.kurosbackend.report.domain.ReportStatus;
import com.kuros.kurosbackend.report.domain.ReportTargetType;

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
