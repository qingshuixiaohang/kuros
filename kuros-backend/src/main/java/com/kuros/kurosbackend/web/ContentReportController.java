package com.kuros.kurosbackend.web;

import cn.dev33.satoken.stp.StpUtil;
import com.kuros.kurosbackend.api.ApiResponse;
import com.kuros.kurosbackend.api.CreateReportRequest;
import com.kuros.kurosbackend.api.ReportResponse;
import com.kuros.kurosbackend.service.ContentReportService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reports")
public class ContentReportController {

    private final ContentReportService reportService;

    public ContentReportController(ContentReportService reportService) {
        this.reportService = reportService;
    }

    @PostMapping("/{targetType}/{targetId}")
    public ResponseEntity<ApiResponse<ReportResponse>> create(
            @PathVariable String targetType,
            @PathVariable String targetId,
            @RequestBody CreateReportRequest request
    ) {
        ReportResponse result = reportService.create(targetType, targetId, StpUtil.getLoginIdAsString(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(result, null));
    }
}
