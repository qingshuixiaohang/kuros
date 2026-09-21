package com.kuros.kurosbackend.comment.web;

import cn.dev33.satoken.stp.StpUtil;
import com.kuros.kurosbackend.shared.api.ApiResponse;
import com.kuros.kurosbackend.comment.api.CommentResponse;
import com.kuros.kurosbackend.comment.api.CreateCommentRequest;
import com.kuros.kurosbackend.shared.api.PageResult;
import com.kuros.kurosbackend.comment.service.CommunityCommentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/posts/{postId}/comments")
public class CommunityCommentController {

    private final CommunityCommentService commentService;

    public CommunityCommentController(CommunityCommentService commentService) {
        this.commentService = commentService;
    }

    @GetMapping
    public ApiResponse<List<CommentResponse>> list(
            @PathVariable String postId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "latest") String sort
    ) {
        PageResult<CommentResponse> result = commentService.findByPost(postId, page, pageSize, sort);
        return new ApiResponse<>(result.items(), result.meta());
    }

    @PostMapping
    public ApiResponse<CommentResponse> create(
            @PathVariable String postId,
            @RequestBody CreateCommentRequest request
    ) {
        return new ApiResponse<>(commentService.create(postId, StpUtil.getLoginIdAsString(), request), null);
    }

    @DeleteMapping("/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable String postId,
            @PathVariable String commentId
    ) {
        commentService.delete(postId, commentId, StpUtil.getLoginIdAsString());
    }
}
