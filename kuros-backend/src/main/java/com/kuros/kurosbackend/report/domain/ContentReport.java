package com.kuros.kurosbackend.report.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "content_reports")
public class ContentReport {

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Column(name = "reporter_id", length = 36, nullable = false)
    private String reporterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", length = 32, nullable = false)
    private ReportTargetType targetType;

    @Column(name = "target_id", length = 36, nullable = false)
    private String targetId;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private ReportReason reason;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private ReportStatus status;

    @Column(name = "handled_by", length = 36)
    private String handledBy;

    @Column(name = "handled_at")
    private LocalDateTime handledAt;

    @Column(name = "handling_note", length = 500)
    private String handlingNote;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected ContentReport() {
    }

    public ContentReport(
            String id,
            String reporterId,
            ReportTargetType targetType,
            String targetId,
            ReportReason reason,
            LocalDateTime now
    ) {
        this.id = id;
        this.reporterId = reporterId;
        this.targetType = targetType;
        this.targetId = targetId;
        this.reason = reason;
        this.status = ReportStatus.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void handle(String handlerId, ReportStatus result, String note, LocalDateTime now) {
        this.status = result;
        this.handledBy = handlerId;
        this.handlingNote = note;
        this.handledAt = now;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getReporterId() { return reporterId; }
    public ReportTargetType getTargetType() { return targetType; }
    public String getTargetId() { return targetId; }
    public ReportReason getReason() { return reason; }
    public ReportStatus getStatus() { return status; }
    public String getHandledBy() { return handledBy; }
    public LocalDateTime getHandledAt() { return handledAt; }
    public String getHandlingNote() { return handlingNote; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
