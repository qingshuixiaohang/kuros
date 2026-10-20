package com.kuros.kurosbackend.echoes.web;

import com.kuros.kurosbackend.echoes.api.EchoResponse;
import com.kuros.kurosbackend.echoes.service.EchoCatalogService;
import com.kuros.kurosbackend.shared.api.ApiResponse;
import com.kuros.kurosbackend.shared.api.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 声骸域公开接口（前端审计 H-9 的第二块）。
 *
 * 游客可读：声骸图鉴是公开内容，与 /api/v1/posts、/api/v1/characters、
 * /api/v1/news 同属 SaTokenConfigure 的公开 GET 白名单——缺了这一条，
 * 未登录用户打开 /echoes 会吃 401（批量互动状态端点踩过同样的坑）。
 */
@RestController
@RequestMapping("/api/v1/echoes")
public class EchoController {

    private final EchoCatalogService service;

    public EchoController(EchoCatalogService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<EchoResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String role
    ) {
        PageResult<EchoResponse> result = service.findVisible(page, pageSize, role);
        return new ApiResponse<>(result.items(), result.meta());
    }

    @GetMapping("/{slug}")
    public ApiResponse<EchoResponse> detail(@PathVariable String slug) {
        return new ApiResponse<>(service.findVisibleBySlug(slug), null);
    }
}
