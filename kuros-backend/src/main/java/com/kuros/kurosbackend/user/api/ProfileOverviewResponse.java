package com.kuros.kurosbackend.user.api;

import com.kuros.kurosbackend.api.PostSummaryResponse;
import com.kuros.kurosbackend.shared.api.PageResult;

public record ProfileOverviewResponse(
        PublicProfileResponse profile,
        ProfileStats stats,
        PageResult<PostSummaryResponse> posts,
        PageResult<ProfileCommentResponse> comments,
        PageResult<PostSummaryResponse> favorites,
        PageResult<PublicProfileResponse> following,
        PageResult<PublicProfileResponse> fans
) {
    public record ProfileStats(long postCount, long likeCount, long commentCount) {
    }
}
