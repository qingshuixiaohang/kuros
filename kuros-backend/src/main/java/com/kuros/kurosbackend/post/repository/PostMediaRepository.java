package com.kuros.kurosbackend.post.repository;

import com.kuros.kurosbackend.post.domain.PostMedia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface PostMediaRepository extends JpaRepository<PostMedia, String> {

    @Query("select count(media) > 0 from PostMedia media, CommunityPost post "
            + "where media.postId = post.id and media.assetId = :assetId "
            + "and post.status = com.kuros.kurosbackend.post.domain.PostStatus.PUBLISHED")
    boolean hasPublishedReference(@Param("assetId") String assetId);

    @Query("select media from PostMedia media where media.postId = :postId and media.inlineMedia = false order by media.sortOrder")
    List<PostMedia> findByPostIdOrderBySortOrderAsc(@Param("postId") String postId);

    List<PostMedia> findAllByPostId(String postId);

    /**
     * 批量取多帖的全部媒体关联（审计 M5 / C1）。
     *
     * 列表读路径必须用这个方法一次性取整页，而不是循环调用上面的单帖版：
     * 后者会让一页 50 帖的 SQL 数达到 102 条（见 PostListSqlCountIntegrationTest）。
     *
     * 返回不带 ORDER BY：排序由调用方按 postId 分组后自行完成
     * （跨帖的全局排序在这里没有意义）。
     */
    @Query("select media from PostMedia media where media.postId in :postIds and media.inlineMedia = false")
    List<PostMedia> findByPostIdIn(@Param("postIds") Collection<String> postIds);

    @Modifying(flushAutomatically = true)
    @Query("delete from PostMedia media where media.postId = :postId")
    void deleteByPostId(@Param("postId") String postId);
}
