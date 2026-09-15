package com.kuros.kurosbackend.api;

public record ProfileOverviewResponse(
        PublicProfileResponse profile,
        ProfileStats stats,
        PageResult<PostSummaryResponse> posts,
        PageResult<ProfileCommentResponse> comments,
        PageResult<PostSummaryResponse> favorites,
        PageResult<PublicProfileResponse> following
) {
    public record ProfileStats(long postCount, long likeCount, long commentCount) {
    }
}
