package com.kuros.kurosbackend.web;

import com.kuros.kurosbackend.api.ApiResponse;
import com.kuros.kurosbackend.api.CreatePostRequest;
import com.kuros.kurosbackend.api.PageResult;
import com.kuros.kurosbackend.api.PostDetailResponse;
import com.kuros.kurosbackend.api.PostSummaryResponse;
import com.kuros.kurosbackend.service.CommunityPostService;
import com.kuros.kurosbackend.service.PostPublishingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.util.List;

@RestController
@RequestMapping("/api/v1/posts")
public class CommunityPostController {

    private final CommunityPostService postService;
    private final PostPublishingService publishingService;

    public CommunityPostController(CommunityPostService postService, PostPublishingService publishingService) {
        this.postService = postService;
        this.publishingService = publishingService;
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

    @PostMapping
    public ResponseEntity<ApiResponse<PostDetailResponse>> publish(@RequestBody CreatePostRequest request, Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(publishingService.publish(request, authentication.getName()), null));
    }

    @GetMapping("/{id}")
    public ApiResponse<PostDetailResponse> detail(@PathVariable String id) {
        return new ApiResponse<>(postService.findPublishedById(id), null);
    }
}
