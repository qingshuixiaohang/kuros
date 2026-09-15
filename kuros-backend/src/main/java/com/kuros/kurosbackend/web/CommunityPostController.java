package com.kuros.kurosbackend.web;

import com.kuros.kurosbackend.api.ApiResponse;
import com.kuros.kurosbackend.api.PageResult;
import com.kuros.kurosbackend.api.PostDetailResponse;
import com.kuros.kurosbackend.api.PostSummaryResponse;
import com.kuros.kurosbackend.service.CommunityPostService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/posts")
public class CommunityPostController {

    private final CommunityPostService postService;

    public CommunityPostController(CommunityPostService postService) {
        this.postService = postService;
    }

    @GetMapping
    public ApiResponse<List<PostSummaryResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "latest") String sort,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String keyword
    ) {
        PageResult<PostSummaryResponse> result = postService.findPublished(page, pageSize, sort, category, tag, keyword);
        return new ApiResponse<>(result.items(), result.meta());
    }

    @GetMapping("/{id}")
    public ApiResponse<PostDetailResponse> detail(@PathVariable String id) {
        return new ApiResponse<>(postService.findPublishedById(id), null);
    }
}
