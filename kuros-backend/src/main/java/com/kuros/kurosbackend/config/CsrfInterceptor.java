package com.kuros.kurosbackend.config;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;
import java.util.UUID;

/**
 * CSRF 双重提交 Cookie 拦截器，替代 Spring Security 的 CookieCsrfTokenRepository。
 *
 * 为什么 SaToken 不内置 CSRF？
 * 因为 SaToken 默认推荐 Header Token 模式（Authorization: Bearer xxx），
 * Header 模式天然不受 CSRF 攻击（浏览器不会自动携带自定义 Header）。
 * 但我们选择 Cookie 模式（对 Next.js SSR 友好），浏览器会自动携带 Cookie，
 * 所以必须自己实现 CSRF 防护。
 *
 * 双重提交 Cookie 原理：
 * 1. 服务端生成随机 Token，写入非 HttpOnly 的 XSRF-TOKEN Cookie（前端 JS 可读）
 * 2. 前端每次写请求时，从 Cookie 读取 Token，放入 X-XSRF-TOKEN Header
 * 3. 服务端校验 Header 值 == Cookie 值
 * 4. 攻击者无法读取跨域 Cookie（同源策略），所以无法伪造 Header
 */
@Component
public class CsrfInterceptor implements HandlerInterceptor {

    private static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    private static final String CSRF_HEADER_NAME = "X-XSRF-TOKEN";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String method = request.getMethod().toUpperCase();

        if (SAFE_METHODS.contains(method)) {
            // 安全方法：确保 CSRF Cookie 存在（前端需要读取它来设置 Header）
            ensureCsrfCookie(request, response);
            return true;
        }

        // 写操作：校验双重提交
        String cookieToken = readCsrfCookie(request);
        String headerToken = request.getHeader(CSRF_HEADER_NAME);

        if (cookieToken == null || headerToken == null || !cookieToken.equals(headerToken)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            try {
                response.getWriter().write("""
                        {"code":"CSRF_TOKEN_MISMATCH","message":"CSRF 令牌校验失败","data":null}
                        """);
            } catch (Exception ignored) {}
            return false;
        }

        return true;
    }

    private void ensureCsrfCookie(HttpServletRequest request, HttpServletResponse response) {
        if (readCsrfCookie(request) == null) {
            String token = UUID.randomUUID().toString();
            Cookie cookie = new Cookie(CSRF_COOKIE_NAME, token);
            cookie.setPath("/");
            cookie.setHttpOnly(false); // 前端 JS 需要读取
            cookie.setMaxAge(-1);       // Session Cookie，浏览器关闭即失效
            response.addCookie(cookie);
        }
    }

    private String readCsrfCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (CSRF_COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
