package com.kuros.kurosbackend.shared.exception;

/**
 * 依赖能力暂不可用 → 503（split-07 引入）。
 *
 * 使用场景：用户域数据表随 split-07 整体迁出本库（V10 后 backend 没有 users 表），
 * 依赖用户资料/关注关系的端点（公开资料、个人中心）在窗口期整端降级，
 * 直到 split-08 经 Feign 从 kuros-user 的内部 API 回填后恢复。
 *
 * 为什么是 503 而不是 404/500：
 * - 404 会误导调用方"用户不存在"——实际是服务端能力暂时缺失；
 * - 500 意味着缺陷，而这里是计划内、可恢复的临时状态（有明确的恢复路径）；
 * - 503 "服务暂不可用"最贴合语义，前端可据此渲染优雅降级提示。
 */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message) {
        super(message);
    }
}
