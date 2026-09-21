package com.kuros.kurosuser.shared.exception;

/**
 * 资源不存在异常（split-07 随关注链路迁入）。
 *
 * 本服务的首个 404 生产者：关注目标用户不存在。
 * 为什么与 backend 版本分开而不是抽公共模块：Q5-A 决策——不建 common 模块，有纪律的复制；
 * 错误码映射由各服务的 ApiExceptionHandler 自行决定（本服务映射 USER_NOT_FOUND）。
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
