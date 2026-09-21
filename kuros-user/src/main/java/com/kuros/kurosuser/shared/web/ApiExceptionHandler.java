package com.kuros.kurosuser.shared.web;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.kuros.kurosuser.shared.api.ErrorResponse;
import com.kuros.kurosuser.shared.exception.AuthRequestException;
import com.kuros.kurosuser.shared.exception.UnauthorizedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 全局异常处理器（split-06 自 kuros-backend 迁入的认证域子集）。
 *
 * 为什么是子集：backend 版还处理内容域错误（ResourceNotFoundException → POST_NOT_FOUND、
 * FileStorageException → FILE_UPLOAD_FAILED），这些异常在 kuros-user 中没有生产者，
 * 随错误码语义留在 backend 的 handler 里（Q15-A 白名单子集原则）。
 * 保留部分 = 认证与鉴权的完整异常面：SaToken 三类 + 认证域两类。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * SaToken 未登录异常 → 401。
     * 替代原来 Spring Security 的 AuthenticationEntryPoint。
     * 当 StpUtil.checkLogin() 失败或 Token 过期时抛出。
     */
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<ErrorResponse> notLogin(NotLoginException exception) {
        String message = switch (exception.getType()) {
            case NotLoginException.NOT_TOKEN -> "请先登录";
            case NotLoginException.INVALID_TOKEN -> "登录已过期，请重新登录";
            case NotLoginException.TOKEN_TIMEOUT -> "登录已过期，请重新登录";
            case NotLoginException.BE_REPLACED -> "您已在其他设备登录";
            case NotLoginException.KICK_OUT -> "您已被强制下线";
            default -> "请先登录";
        };
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("UNAUTHORIZED", message, null));
    }

    /**
     * SaToken 角色不足异常 → 403。
     * 当 StpUtil.checkRole("ADMIN") 失败时抛出。
     * 保留原因：StpInterfaceImpl 的归属在本服务，角色校验的异常面随认证域整体迁移
     * （用户域后续新增角色保护端点时不再需要回 backend 抄 handler）。
     */
    @ExceptionHandler(NotRoleException.class)
    public ResponseEntity<ErrorResponse> notRole(NotRoleException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("FORBIDDEN", "无权执行此操作", null));
    }

    /**
     * SaToken 权限不足异常 → 403。
     * 当 StpUtil.checkPermission("report:handle") 失败时抛出。
     */
    @ExceptionHandler(NotPermissionException.class)
    public ResponseEntity<ErrorResponse> notPermission(NotPermissionException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("FORBIDDEN", "无权执行此操作", null));
    }

    @ExceptionHandler(AuthRequestException.class)
    public ResponseEntity<ErrorResponse> authRequest(AuthRequestException exception) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(exception.getCode(), exception.getMessage(), Map.of()));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> unauthorized(UnauthorizedException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("UNAUTHORIZED", exception.getMessage(), null));
    }
}
