package com.kuros.kurosbackend.web;

import com.kuros.kurosbackend.api.ApiResponse;
import com.kuros.kurosbackend.api.CharacterResponse;
import com.kuros.kurosbackend.api.PageResult;
import com.kuros.kurosbackend.service.CharacterCatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/characters")
public class CharacterController {

    private final CharacterCatalogService service;

    public CharacterController(CharacterCatalogService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<CharacterResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int pageSize,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String keyword
    ) {
        PageResult<CharacterResponse> result = service.findVisible(page, pageSize, role, keyword);
        return new ApiResponse<>(result.items(), result.meta());
    }

    @GetMapping("/{slug}")
    public ApiResponse<CharacterResponse> detail(@PathVariable String slug) {
        return new ApiResponse<>(service.findVisibleBySlug(slug), null);
    }
}
