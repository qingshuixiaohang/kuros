package com.kuros.kurosuser.shared.web;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.kuros.kurosuser.shared.api.ErrorResponse;
import com.kuros.kurosuser.shared.exception.AuthRequestException;
import com.kuros.kurosuser.shared.exception.ResourceNotFoundException;
import com.kuros.kurosuser.shared.exception.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理器（split-06 自 kuros-backend 迁入的认证域子集）。
 *
 * 为什么是子集：backend 版还处理内容域错误（POST_NOT_FOUND、COMMENT_NOT_FOUND、
 * FileStorageException → FILE_UPLOAD_FAILED），这些异常在 kuros-user 中没有生产者，
 * 随错误码语义留在 backend 的 handler 里（Q15-A 白名单子集原则）。
 * 保留部分 = 认证与鉴权的完整异常面：SaToken 三类 + 认证域两类。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

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
                .body(new ErrorResponse(exception.getCode(), exception.getMessage(), null));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> unauthorized(UnauthorizedException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("UNAUTHORIZED", exception.getMessage(), null));
    }

    /**
     * 资源不存在异常 → 404（split-07 随关注链迁入）。
     *
     * 为什么错误码是 USER_NOT_FOUND 而不是 backend 那样的资源前缀码
     * （POST_NOT_FOUND 等）：本服务的 404 生产者当前只有关注链的用户查询
     * （公开 follow 端点的目标校验 + 内部 API 的 follow-stats/following/followers），
     * 语义上全是“用户不存在”，用具体错误码让前端/调用方免于猜测资源类型；
     * 未来若本服务出现其他资源 404，再按类型拆分专用码。
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> resourceNotFound(ResourceNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("USER_NOT_FOUND", exception.getMessage(), null));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> unreadableRequest(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("INVALID_REQUEST", "请求体格式不正确", null));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> typeMismatch(MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("INVALID_REQUEST", "请求参数不正确", null));
    }

    @ExceptionHandler(ServletRequestBindingException.class)
    public ResponseEntity<ErrorResponse> missingRequestValue(ServletRequestBindingException exception) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("INVALID_REQUEST", "缺少必需的请求参数", null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException exception) {
        return validationFailure(fieldErrors(exception.getBindingResult()));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ErrorResponse> binding(BindException exception) {
        return validationFailure(fieldErrors(exception.getBindingResult()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> uploadTooLarge(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ErrorResponse("UPLOAD_TOO_LARGE", "上传文件过大", null));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> methodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new ErrorResponse("METHOD_NOT_ALLOWED", "请求方法不受支持", null));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> mediaTypeNotSupported(HttpMediaTypeNotSupportedException exception) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(new ErrorResponse("UNSUPPORTED_MEDIA_TYPE", "请求内容类型不受支持", null));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> noResource(NoResourceFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("NOT_FOUND", "请求的资源不存在", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception exception) {
        log.error("Unhandled API exception", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", "服务器内部错误，请稍后重试", null));
    }

    private ResponseEntity<ErrorResponse> validationFailure(Map<String, String> fieldErrors) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("VALIDATION_FAILED", "请求参数校验失败", fieldErrors));
    }

    private Map<String, String> fieldErrors(BindingResult result) {
        return result.getFieldErrors().stream()
                .collect(LinkedHashMap::new,
                        (errors, error) -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()),
                        LinkedHashMap::putAll);
    }
}
