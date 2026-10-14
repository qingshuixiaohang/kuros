package com.kuros.kurosbackend.news.web;

import com.kuros.kurosbackend.news.api.NewsResponse;
import com.kuros.kurosbackend.news.service.NewsCatalogService;
import com.kuros.kurosbackend.shared.api.ApiResponse;
import com.kuros.kurosbackend.shared.api.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 资讯域公开接口（前端审计 H-9）。
 *
 * 游客可读：资讯是公开内容，与 /api/v1/posts、/api/v1/characters 同属
 * SaTokenConfigure 的公开 GET 白名单——缺了这一条，未登录用户打开 /news
 * 会吃 401（批量互动状态端点踩过同样的坑）。
 */
@RestController
@RequestMapping("/api/v1/news")
public class NewsController {

    private final NewsCatalogService service;

    public NewsController(NewsCatalogService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<NewsResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String category
    ) {
        PageResult<NewsResponse> result = service.findVisible(page, pageSize, category);
        return new ApiResponse<>(result.items(), result.meta());
    }

    @GetMapping("/{slug}")
    public ApiResponse<NewsResponse> detail(@PathVariable String slug) {
        return new ApiResponse<>(service.findVisibleBySlug(slug), null);
    }
}
