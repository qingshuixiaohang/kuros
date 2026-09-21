package com.kuros.kurosbackend.web;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.kuros.kurosbackend.shared.api.ErrorResponse;
import com.kuros.kurosbackend.shared.exception.ResourceNotFoundException;
import com.kuros.kurosbackend.shared.exception.AuthRequestException;
import com.kuros.kurosbackend.shared.exception.UnauthorizedException;
import com.kuros.kurosbackend.shared.exception.ForbiddenException;
import com.kuros.kurosbackend.shared.exception.FileStorageException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

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

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> resourceNotFound(ResourceNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("POST_NOT_FOUND", exception.getMessage(), null));
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

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> forbidden(ForbiddenException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("FORBIDDEN", exception.getMessage(), null));
    }

    @ExceptionHandler(FileStorageException.class)
    public ResponseEntity<ErrorResponse> fileStorage(FileStorageException exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("FILE_UPLOAD_FAILED", exception.getMessage(), null));
    }
}
