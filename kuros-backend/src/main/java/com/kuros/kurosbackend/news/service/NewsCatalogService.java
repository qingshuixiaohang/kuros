package com.kuros.kurosbackend.news.service;

import com.kuros.kurosbackend.news.api.NewsResponse;
import com.kuros.kurosbackend.news.domain.NewsArticle;
import com.kuros.kurosbackend.news.domain.NewsParagraph;
import com.kuros.kurosbackend.news.repository.NewsArticleRepository;
import com.kuros.kurosbackend.shared.api.PageMeta;
import com.kuros.kurosbackend.shared.api.PageResult;
import com.kuros.kurosbackend.shared.exception.ResourceNotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class NewsCatalogService {

    /** 每页上限：资讯是低频内容，50 条足够覆盖任意筛选结果，也防得住 pageSize=99999。 */
    private static final int MAX_PAGE_SIZE = 50;

    private final NewsArticleRepository repository;

    public NewsCatalogService(NewsArticleRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public PageResult<NewsResponse> findVisible(int page, int pageSize, String category) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage - 1, safeSize);

        var result = category == null || category.isBlank()
                ? repository.findByEnabledTrue(pageable)
                : repository.findByEnabledTrueAndCategory(category, pageable);

        List<NewsResponse> items = result.getContent().stream()
                .map(NewsCatalogService::toResponse)
                .toList();
        // PageResult 的形状是 (items, meta)，meta 自带 page/pageSize/totalItems/totalPages
        return new PageResult<>(items, new PageMeta(safePage, safeSize, result.getTotalElements(), result.getTotalPages()));
    }

    @Transactional(readOnly = true)
    public NewsResponse findVisibleBySlug(String slug) {
        NewsArticle article = repository.findBySlugAndEnabledTrue(slug)
                .orElseThrow(() -> new ResourceNotFoundException("NEWS_NOT_FOUND", "资讯不存在或已下线"));
        return toResponse(article);
    }

    /**
     * 段落已在 {@code findBySlugAndEnabledTrue} 的 EntityGraph 里一起取回，
     * 这里只做顺序映射（JPA 侧 @OrderBy 已保证顺序，这里不再排一次）。
     */
    private static NewsResponse toResponse(NewsArticle article) {
        List<String> paragraphs = article.getParagraphs().stream()
                .map(NewsParagraph::getContent)
                .toList();
        return new NewsResponse(
                article.getId(),
                article.getSlug(),
                article.getTitle(),
                article.getCategory(),
                article.getSummary(),
                article.getPublishedAt(),
                article.isHot(),
                paragraphs);
    }
}
