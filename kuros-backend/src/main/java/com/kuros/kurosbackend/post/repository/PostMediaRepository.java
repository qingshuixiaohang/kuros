package com.kuros.kurosbackend.post.repository;

import com.kuros.kurosbackend.post.domain.PostMedia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface PostMediaRepository extends JpaRepository<PostMedia, String> {

    List<PostMedia> findByPostIdOrderBySortOrderAsc(String postId);

    /**
     * 批量取多帖的全部媒体关联（审计 M5 / C1）。
     *
     * 列表读路径必须用这个方法一次性取整页，而不是循环调用上面的单帖版：
     * 后者会让一页 50 帖的 SQL 数达到 102 条（见 PostListSqlCountIntegrationTest）。
     *
     * 返回不带 ORDER BY：排序由调用方按 postId 分组后自行完成
     * （跨帖的全局排序在这里没有意义）。
     */
    List<PostMedia> findByPostIdIn(Collection<String> postIds);

    @Modifying(flushAutomatically = true)
    @Query("delete from PostMedia media where media.postId = :postId")
    void deleteByPostId(@Param("postId") String postId);
}
