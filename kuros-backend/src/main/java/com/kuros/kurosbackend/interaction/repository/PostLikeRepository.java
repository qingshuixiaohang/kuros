package com.kuros.kurosbackend.interaction.repository;

import com.kuros.kurosbackend.interaction.domain.PostLike;
import com.kuros.kurosbackend.interaction.domain.PostLikeId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
