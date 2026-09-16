package com.kuros.kurosbackend.web;

import com.kuros.kurosbackend.api.ApiResponse;
import com.kuros.kurosbackend.api.PageResult;
import com.kuros.kurosbackend.api.PostSummaryResponse;
import com.kuros.kurosbackend.api.ProfileOverviewResponse;
import com.kuros.kurosbackend.api.PublicProfileResponse;
import com.kuros.kurosbackend.service.ProfileService;
import org.springframework.security.core.Authentication;
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
            @RequestParam(defaultValue = "20") int pageSize,
            Authentication authentication
    ) {
        return new ApiResponse<>(profileService.findOwn(authentication.getName(), page, pageSize), null);
    }
}
