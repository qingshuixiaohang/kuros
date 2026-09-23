package com.kuros.kurosbackend.search;

import com.kuros.kurosbackend.shared.api.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 搜索运维端点（切片 #14 se-03）。
 *
 * <p>为什么落在 {@code /api/v1/admin/**} 而非工单草案的 {@code /api/v1/internal/**}：
 * backend 的 SaTokenConfigure 只对 {@code /api/v1/admin/**} 施加 {@code checkRole("ADMIN")}，
 * 且 backend 侧并无 {@code /internal/**} 免鉴权约定（那是 kuros-user 的入站内部 API 前缀）。
 * 复用现成的 ADMIN 角色门即得到「仅运维可触发」的语义，无需为全量重建新扩鉴权白名单——
 * 与 AdminReportController 同一约定（对齐既有代码而非引入新前缀）。
 *
 * <p>全量重建是重操作（遍历全表 + bulk 写新索引），故设为 POST + ADMIN + CSRF（/api/** 天然覆盖），
 * 防误触；零停机由 {@link SearchIndexManager} 的版本索引 + 原子切别名保证，重建期间搜索不中断。
 */
@RestController
@RequestMapping("/api/v1/admin/search")
public class AdminSearchController {

    private final PostIndexService postIndexService;

    public AdminSearchController(PostIndexService postIndexService) {
        this.postIndexService = postIndexService;
    }

    /** 触发帖子全文索引零停机全量重建，返回本次索引的文档数。 */
    @PostMapping("/reindex")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reindex() {
        long indexed = postIndexService.reindexAll();
        return ResponseEntity.ok(new ApiResponse<>(Map.of("indexed", indexed), null));
    }
}
