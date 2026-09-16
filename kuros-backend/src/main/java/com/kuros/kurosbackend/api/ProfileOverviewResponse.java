package com.kuros.kurosbackend.api;

public record ProfileOverviewResponse(
        PublicProfileResponse profile,
        ProfileStats stats,
        PageResult<PostSummaryResponse> posts,
        PageResult<ProfileCommentResponse> comments
) {
    public record ProfileStats(long postCount, long likeCount, long commentCount) {
    }
}
