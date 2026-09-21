package com.kuros.kurosuser.api;

import com.kuros.kurosuser.domain.CommunityUser;

/**
 * 用户摘要（split-07 新增，内部 API 的返回单元）。
 *
 * 字段面：工单要求 {id, nickname, avatarUrl}，额外携带 bio——
 * split-08 的 backend 作者组装（AuthorResponse）需要 bio 完整回填，
 * 提前纳入可避免二次改契约；都是 users 表的直接投影，无额外查询成本。
 *
 * 为什么是"摘要"而不是完整用户档案：内部 API 的消费方只做组装
 * （作者卡片/关注列表行），手机号、状态等敏感字段不进内部契约面——
 * 数据最小化原则与公开 API 一致。
 */
public record UserBriefResponse(String id, String nickname, String avatarUrl, String bio) {

    public static UserBriefResponse from(CommunityUser user) {
        return new UserBriefResponse(user.getId(), user.getNickname(), user.getAvatarUrl(), user.getBio());
    }
}
