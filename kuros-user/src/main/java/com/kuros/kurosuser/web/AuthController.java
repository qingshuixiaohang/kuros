package com.kuros.kurosuser.web;

import com.kuros.kurosuser.api.AuthUserResponse;
import com.kuros.kurosuser.api.PhoneCodeRequest;
import com.kuros.kurosuser.api.PhoneLoginRequest;
import com.kuros.kurosuser.api.VerificationCodeResponse;
import com.kuros.kurosuser.service.AuthService;
import com.kuros.kurosuser.shared.api.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 认证控制器（split-06 自 kuros-backend 迁入，API 契约逐字不变）。
 *
 * 迁移动机：认证链路的权威归属整体搬到用户服务——前端经网关的
 * /api/v1/auth/** 全部路由到本服务；backend 侧同路径已无 handler（直连 404）。
 *
 * 与迁移前的核心区别（保留原设计注释）：
 * - 登录不再手动构建 ResponseCookie，SaToken 的 StpUtil.login() 自动设置 Cookie
 * - 登出不再手动清除 Cookie，SaToken 的 StpUtil.logout() 自动处理
 * - /me 不再从 Cookie 手动读 token，SaToken 自动从请求中解析 Token
 * - /csrf 端点保留（前端 refreshCsrfCookie() 仍然调用它）
 *
 * API 契约完全不变：路径、请求体、响应体格式与迁移前一致，前端零改动。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/code")
    public ApiResponse<VerificationCodeResponse> code(@RequestBody PhoneCodeRequest request) {
        return new ApiResponse<>(authService.issueCode(request), null);
    }

    /**
     * CSRF Token 端点（保留）。
     * 前端 api.ts 的 refreshCsrfCookie() 会调用这个端点获取 XSRF-TOKEN Cookie。
     * 虽然 CsrfInterceptor 在 GET 请求时也会自动设置，但保留这个端点保证前端逻辑不变。
     */
    @GetMapping("/csrf")
    public ResponseEntity<Void> csrf(HttpServletResponse response) {
        String token = UUID.randomUUID().toString();
        jakarta.servlet.http.Cookie cookie = new jakarta.servlet.http.Cookie("XSRF-TOKEN", token);
        cookie.setPath("/");
        cookie.setHttpOnly(false);
        cookie.setMaxAge(-1);
        response.addCookie(cookie);
        return ResponseEntity.noContent().build();
    }

    /**
     * 登录。
     * SaToken 的 StpUtil.login() 会自动在 response 中设置 KUROS_SESSION Cookie，
     * 不需要手动构建 ResponseCookie。
     */
    @PostMapping("/login")
    public ApiResponse<AuthUserResponse> login(@RequestBody PhoneLoginRequest request) {
        AuthService.LoginResult result = authService.login(request);
        return new ApiResponse<>(result.user(), null);
    }

    /**
     * 获取当前登录用户。
     * SaToken 自动从请求 Cookie 中解析 Token，不需要手动读取。
     */
    @GetMapping("/me")
    public ApiResponse<AuthUserResponse> me() {
        return new ApiResponse<>(authService.currentUser(), null);
    }

    /**
     * 登出。
     * SaToken 的 StpUtil.logout() 会自动删除 Redis 中的 Token 并清除 Cookie。
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        authService.logout();
        return ResponseEntity.noContent().build();
    }
}
