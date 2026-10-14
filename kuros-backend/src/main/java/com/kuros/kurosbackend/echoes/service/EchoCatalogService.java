package com.kuros.kurosbackend.echoes.service;

import com.kuros.kurosbackend.echoes.api.EchoResponse;
import com.kuros.kurosbackend.echoes.domain.EchoSet;
import com.kuros.kurosbackend.echoes.repository.EchoSetRepository;
import com.kuros.kurosbackend.shared.api.PageMeta;
import com.kuros.kurosbackend.shared.api.PageResult;
import com.kuros.kurosbackend.shared.exception.ResourceNotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class EchoCatalogService {

    /** 每页上限：声骸图鉴是静态内容，50 条足够覆盖任意筛选结果，也防得住 pageSize=99999。 */
    private static final int MAX_PAGE_SIZE = 50;

    private final EchoSetRepository repository;

    public EchoCatalogService(EchoSetRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public PageResult<EchoResponse> findVisible(int page, int pageSize, String role) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage - 1, safeSize);

        var result = role == null || role.isBlank()
                ? repository.findByEnabledTrue(pageable)
                : repository.findByEnabledTrueAndRole(role, pageable);

        List<EchoResponse> items = result.getContent().stream()
                .map(EchoCatalogService::toResponse)
                .toList();
        return new PageResult<>(items, new PageMeta(safePage, safeSize, result.getTotalElements(), result.getTotalPages()));
    }

    @Transactional(readOnly = true)
    public EchoResponse findVisibleBySlug(String slug) {
        EchoSet echo = repository.findBySlugAndEnabledTrue(slug)
                .orElseThrow(() -> new ResourceNotFoundException("ECHO_NOT_FOUND", "声骸套装不存在或已下架"));
        return toResponse(echo);
    }

    private static EchoResponse toResponse(EchoSet echo) {
        return new EchoResponse(
                echo.getId(),
                echo.getSlug(),
                echo.getName(),
                echo.getEffect(),
                echo.getLocation(),
                echo.getRole(),
                echo.getDescription(),
                echo.getGuideSlug());
    }
}
