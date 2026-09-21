package com.kuros.kurosuser.repository;

import com.kuros.kurosuser.domain.UserFollow;
import com.kuros.kurosuser.domain.UserFollowId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 关注关系仓储（split-07 自 kuros-backend 迁入，方法签名与查询逐字一致）。
 *
 * 迁移后的权威归属：关注关系只写在本库（kuros_user），backend 的副本已随 V10 删除。
 * 分页查询沿用"createdAt 倒序"：关注/粉丝列表的新近性排序不变。
 */
public interface UserFollowRepository extends JpaRepository<UserFollow, UserFollowId> {

    long countByFollowedId(String followedId);

    Page<UserFollow> findByFollowerIdOrderByCreatedAtDesc(String followerId, Pageable pageable);

    Page<UserFollow> findByFollowedIdOrderByCreatedAtDesc(String followedId, Pageable pageable);
}
