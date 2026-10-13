package com.kuros.kurosbackend.interaction.repository;

import com.kuros.kurosbackend.interaction.domain.PostLike;
import com.kuros.kurosbackend.interaction.domain.PostLikeId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface PostLikeRepository extends JpaRepository<PostLike, PostLikeId> {

    /**
     * 统计某帖的真实点赞数（审计 M5 / B3：计数对账的真相来源）。
     *
     * 为什么对账要用 COUNT(*) 而不是读 posts.like_count 冗余列？
     * 冗余列是「增量更新」的产物——每次 LIKE/UNLIKE 投影时
     * increment/decrementLikeCount。增量更新在事件丢失时会永久偏移，
     * 而关系表的行数是幂等的、可重算的：同一批事件重复消费不会让行数变化。
     * 所以 COUNT(*) 才是能自我修复的真相，冗余列只是读优化。
     */
    @Query("select count(l) from PostLike l where l.postId = :postId")
    long countByPostId(@Param("postId") String postId);

    /**
     * 批量查某用户对一批帖子的点赞关系（首页 Feed N+1 消除，审计 H-2）。
     *
     * 为什么必须批量：首页一屏 20 帖，逐帖查「当前用户是否已赞」就是 20 次
     * 查询（浏览器还有 6 条并发上限，实际分 4 批完成）。而列表接口早已返回
     * likeCount/favoriteCount，卡片计数不需要额外请求——N+1 只发生在这个
     * per-user 关系状态上。一次 IN 查询把它压成常数。
     */
    @Query("select l.postId from PostLike l where l.userId = :userId and l.postId in :postIds")
    List<String> findLikedPostIds(@Param("userId") String userId, @Param("postIds") Collection<String> postIds);
}
