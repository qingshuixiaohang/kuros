package com.kuros.kurosbackend.web;

import cn.dev33.satoken.stp.StpUtil;
import com.kuros.kurosbackend.shared.api.ApiResponse;
import com.kuros.kurosbackend.shared.api.PageResult;
import com.kuros.kurosbackend.api.PostSummaryResponse;
import com.kuros.kurosbackend.api.ProfileOverviewResponse;
import com.kuros.kurosbackend.api.PublicProfileResponse;
import com.kuros.kurosbackend.service.ProfileService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping("/{userId}")
    public ApiResponse<PublicProfileResponse> publicProfile(@PathVariable String userId) {
        return new ApiResponse<>(profileService.findPublic(userId), null);
    }

    @GetMapping("/{userId}/posts")
    public ApiResponse<java.util.List<PostSummaryResponse>> publicPosts(
            @PathVariable String userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize
    ) {
        PageResult<PostSummaryResponse> result = profileService.findPublicPosts(userId, page, pageSize);
        return new ApiResponse<>(result.items(), result.meta());
    }

    @GetMapping("/me/profile")
    public ApiResponse<ProfileOverviewResponse> ownProfile(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize
    ) {
        return new ApiResponse<>(profileService.findOwn(StpUtil.getLoginIdAsString(), page, pageSize), null);
    }
}
