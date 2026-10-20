package com.kuros.kurosbackend.post.service;

import java.util.function.LongSupplier;

public interface PostInteractionCountReader {

    PostInteractionCounts readForPost(String postId, LongSupplier likeBaseline, LongSupplier favoriteBaseline);
}
