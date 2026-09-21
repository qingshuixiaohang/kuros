package com.kuros.kurosbackend.user.client;

/**
 * kuros-user 内部 API 的用户摘要（split-08 Feign 解码目标）。
 *
 * 字段与 kuros-user 的 UserBriefResponse 逐字一致（id / nickname / avatarUrl / bio），
 * 保证 Feign + Jackson 解码零适配。backend 侧独立定义而非共享模块——
 * 两个工程是独立 Maven 项目，内部契约面用"同形 record"对齐即可，
 * 引入共享 jar 反而增加发布耦合（拆分的初衷就是解耦）。
 *
 * 为什么带 bio：内容域作者卡片（AuthorResponse）需要 bio 完整回填，
 * 提前纳入避免二次改契约（与 kuros-user 侧 UserBriefResponse 的设计理由一致）。
 */
public record UserBriefDto(String id, String nickname, String avatarUrl, String bio) {
}
