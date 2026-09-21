package com.kuros.kurosuser.shared.exception;

/**
 * 请求参数类错误（split-06 自 kuros-backend 迁入）。
 * 带业务错误码（如 INVALID_PHONE / CODE_RATE_LIMITED），由 ApiExceptionHandler 转为 400。
 * 认证域内所有"用户输入不合法"的失败都走这个异常，保证错误码契约稳定。
 */
public class AuthRequestException extends RuntimeException {

    private final String code;

    public AuthRequestException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
