package com.kuros.kurosbackend.report.service;

import com.kuros.kurosbackend.report.api.CreateReportRequest;
import com.kuros.kurosbackend.report.api.HandleReportRequest;
import com.kuros.kurosbackend.shared.api.PageMeta;
import com.kuros.kurosbackend.shared.api.PageResult;
import com.kuros.kurosbackend.report.api.ReportResponse;
import com.kuros.kurosbackend.comment.service.CommunityCommentService;
import com.kuros.kurosbackend.report.domain.ContentReport;
import com.kuros.kurosbackend.report.domain.ReportReason;
import com.kuros.kurosbackend.report.domain.ReportStatus;
import com.kuros.kurosbackend.report.domain.ReportTargetType;
import com.kuros.kurosbackend.post.service.PostPublishingService;
import com.kuros.kurosbackend.shared.exception.AuthRequestException;
import com.kuros.kurosbackend.shared.exception.ResourceNotFoundException;
import com.kuros.kurosbackend.report.repository.ContentReportRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ContentReportService {

    private static final int MAX_PAGE_SIZE = 50;

    private final ContentReportRepository reportRepository;
    private final PostPublishingService postPublishingService;
    private final CommunityCommentService commentService;

    public ContentReportService(
            ContentReportRepository reportRepository,
            PostPublishingService postPublishingService,
            CommunityCommentService commentService
    ) {
        this.reportRepository = reportRepository;
        this.postPublishingService = postPublishingService;
        this.commentService = commentService;
    }

    @Transactional
    public ReportResponse create(String targetTypeValue, String targetId, String reporterId, CreateReportRequest request) {
        ReportTargetType targetType = parseTargetType(targetTypeValue);
        ReportReason reason = parseReason(request == null ? null : request.reason());
        String normalizedTargetId = targetId == null ? "" : targetId.trim();
        if (normalizedTargetId.isEmpty()) {
            throw new AuthRequestException("REPORT_TARGET_REQUIRED", "举报目标不能为空");
        }
        ensureReportableTarget(targetType, normalizedTargetId);
        if (reportRepository.existsByReporterIdAndTargetTypeAndTargetIdAndStatus(
                reporterId, targetType, normalizedTargetId, ReportStatus.PENDING)) {
            throw new AuthRequestException("REPORT_DUPLICATE", "你已经举报过该内容，请等待管理员处理");
        }
        LocalDateTime now = LocalDateTime.now();
        return toResponse(reportRepository.save(new ContentReport(
                UUID.randomUUID().toString(), reporterId, targetType, normalizedTargetId, reason, now
        )));
    }

    @Transactional(readOnly = true)
    public PageResult<ReportResponse> findForAdmin(int page, int pageSize, String statusValue) {
        int normalizedPage = Math.max(page, 1);
        int normalizedPageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(normalizedPage - 1, normalizedPageSize,
                Sort.by(Sort.Order.desc("createdAt")));
        Page<ContentReport> reports;
        if (statusValue == null || statusValue.isBlank()) {
            reports = reportRepository.findAll(pageable);
        } else {
            reports = reportRepository.findByStatus(parseStatus(statusValue), pageable);
        }
        TargetContexts contexts = loadTargetContexts(reports.getContent());
        return new PageResult<>(reports.getContent().stream().map(report -> toResponse(report, contexts)).toList(),
                new PageMeta(normalizedPage, normalizedPageSize, reports.getTotalElements(), reports.getTotalPages()));
    }

    @Transactional
    public ReportResponse handle(String reportId, String adminId, HandleReportRequest request) {
        ContentReport report = reportRepository.findByIdForUpdate(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("REPORT_NOT_FOUND", "举报记录不存在"));
        if (report.getStatus() != ReportStatus.PENDING) {
            throw new AuthRequestException("REPORT_ALREADY_HANDLED", "该举报已经处理过了");
        }
        ReportStatus result = parseAction(request == null ? null : request.action());
        String note = request == null || request.note() == null ? null : request.note().trim();
        if (note != null && note.length() > 500) {
            throw new AuthRequestException("REPORT_NOTE_TOO_LONG", "处理备注不能超过 500 个字符");
        }
        if (result == ReportStatus.CONFIRMED) {
            applyDisposition(report);
        }
        report.handle(adminId, result, note, LocalDateTime.now());
        return toResponse(reportRepository.save(report));
    }

    private void ensureReportableTarget(ReportTargetType targetType, String targetId) {
        if (targetType == ReportTargetType.POST) {
            postPublishingService.ensurePublished(targetId);
            return;
        }
        commentService.ensureReportable(targetId);
    }

    private void applyDisposition(ContentReport report) {
        if (report.getTargetType() == ReportTargetType.POST) {
            postPublishingService.deleteForModeration(report.getTargetId());
            return;
        }
        commentService.deleteForModeration(report.getTargetId());
    }

    private ReportTargetType parseTargetType(String value) {
        try {
            return ReportTargetType.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new AuthRequestException("REPORT_TARGET_INVALID", "举报目标类型无效");
        }
    }

    private ReportReason parseReason(String value) {
        try {
            return ReportReason.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new AuthRequestException("REPORT_REASON_INVALID", "举报理由无效");
        }
    }

    private ReportStatus parseStatus(String value) {
        try {
            return ReportStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new AuthRequestException("REPORT_STATUS_INVALID", "举报状态无效");
        }
    }

    private ReportStatus parseAction(String value) {
        if ("CONFIRM".equalsIgnoreCase(value)) return ReportStatus.CONFIRMED;
        if ("REJECT".equalsIgnoreCase(value)) return ReportStatus.REJECTED;
        throw new AuthRequestException("REPORT_ACTION_INVALID", "处理动作必须是 CONFIRM 或 REJECT");
    }

    private TargetContexts loadTargetContexts(Collection<ContentReport> reports) {
        Set<String> commentIds = reports.stream()
                .filter(report -> report.getTargetType() == ReportTargetType.COMMENT)
                .map(ContentReport::getTargetId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, CommunityCommentService.ReportTargetContext> comments =
                commentService.findReportTargetContexts(commentIds);

        Set<String> postIds = reports.stream()
                .filter(report -> report.getTargetType() == ReportTargetType.POST)
                .map(ContentReport::getTargetId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        comments.values().stream()
                .map(CommunityCommentService.ReportTargetContext::postId)
                .filter(Objects::nonNull)
                .forEach(postIds::add);

        return new TargetContexts(comments, postPublishingService.findReportTargetContexts(postIds));
    }

    private ReportResponse toResponse(ContentReport report) {
        return toResponse(report, new TargetContexts(Map.of(), Map.of()));
    }

    private ReportResponse toResponse(ContentReport report, TargetContexts contexts) {
        PostPublishingService.ReportTargetContext post = null;
        String targetExcerpt = null;
        String targetPostId = null;
        if (report.getTargetType() == ReportTargetType.POST) {
            post = contexts.posts().get(report.getTargetId());
            targetExcerpt = post == null ? null : post.excerpt();
            targetPostId = post == null ? null : post.postId();
        } else {
            CommunityCommentService.ReportTargetContext comment = contexts.comments().get(report.getTargetId());
            if (comment != null) {
                targetExcerpt = comment.content();
                targetPostId = comment.postId();
                post = contexts.posts().get(comment.postId());
            }
        }
        return new ReportResponse(report.getId(), report.getReporterId(), report.getTargetType(), report.getTargetId(),
                post == null ? null : post.title(), targetExcerpt, targetPostId,
                report.getReason(), report.getStatus(), report.getHandledBy(), report.getHandledAt(),
                report.getHandlingNote(), report.getCreatedAt());
    }

    private record TargetContexts(
            Map<String, CommunityCommentService.ReportTargetContext> comments,
            Map<String, PostPublishingService.ReportTargetContext> posts
    ) {
    }
}
