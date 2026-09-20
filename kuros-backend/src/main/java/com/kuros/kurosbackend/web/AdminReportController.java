package com.kuros.kurosbackend.web;

import cn.dev33.satoken.stp.StpUtil;
import com.kuros.kurosbackend.api.ApiResponse;
import com.kuros.kurosbackend.api.HandleReportRequest;
import com.kuros.kurosbackend.api.PageResult;
import com.kuros.kurosbackend.api.ReportResponse;
import com.kuros.kurosbackend.service.ContentReportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/reports")
public class AdminReportController {

    private final ContentReportService reportService;

    public AdminReportController(ContentReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping
    public ApiResponse<java.util.List<ReportResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String status
    ) {
        PageResult<ReportResponse> result = reportService.findForAdmin(page, pageSize, status);
        return new ApiResponse<>(result.items(), result.meta());
    }

    @PostMapping("/{reportId}/handle")
    public ResponseEntity<ApiResponse<ReportResponse>> handle(
            @PathVariable String reportId,
            @RequestBody HandleReportRequest request
    ) {
        ReportResponse result = reportService.handle(reportId, StpUtil.getLoginIdAsString(), request);
        return ResponseEntity.ok(new ApiResponse<>(result, null));
    }
}
