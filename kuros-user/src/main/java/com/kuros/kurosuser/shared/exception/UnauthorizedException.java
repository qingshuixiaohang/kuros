package com.kuros.kurosuser.shared.exception;

/**
 * 未认证错误（split-06 自 kuros-backend 迁入）。
 * AuthService 在"会话存在但用户行缺失"或"未登录访问 /me"时抛出，由 handler 转为 401；
 * SaToken 自身的 NotLoginException 也映射到同一状态码与错误码，保证 401 契约一致。
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
