package com.kuros.kurosbackend.post.web;

import cn.dev33.satoken.stp.StpUtil;
import com.kuros.kurosbackend.shared.api.ApiResponse;
import com.kuros.kurosbackend.post.api.CreatePostRequest;
import com.kuros.kurosbackend.shared.api.PageResult;
import com.kuros.kurosbackend.post.api.PostDetailResponse;
import com.kuros.kurosbackend.post.api.PostSummaryResponse;
import com.kuros.kurosbackend.post.service.CommunityPostService;
import com.kuros.kurosbackend.post.service.PostPublishingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

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
    public ResponseEntity<ApiResponse<PostDetailResponse>> publish(@RequestBody CreatePostRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(publishingService.publish(request, StpUtil.getLoginIdAsString()), null));
    }

    @PutMapping("/{id}")
    public ApiResponse<PostDetailResponse> update(@PathVariable String id, @RequestBody CreatePostRequest request) {
        return new ApiResponse<>(publishingService.update(id, StpUtil.getLoginIdAsString(), request), null);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        publishingService.delete(id, StpUtil.getLoginIdAsString());
    }

    @GetMapping("/{id}")
    public ApiResponse<PostDetailResponse> detail(@PathVariable String id) {
        return new ApiResponse<>(postService.findPublishedById(id), null);
    }
}
