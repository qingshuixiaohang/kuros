package com.kuros.kurosbackend.config;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * Sentinel 限流拦截器。
 *
 * 为什么用自定义 HandlerInterceptor 而不是 sentinel-spring-webmvc-v6x-adapter？
 * 1. Spring Boot 4.x 基于 Jakarta EE（jakarta.servlet.*），适配器可能不兼容
 * 2. 自定义实现只有 50 行，逻辑透明可控
 * 3. 可以精确控制哪些路径需要限流，哪些跳过
 *
 * SphU.entry() / entry.exit() 必须成对出现，否则 Sentinel 内部计数会泄漏。
 * 用 try-finally 保证 exit() 一定被调用。
 */
public class SentinelRateLimitInterceptor implements HandlerInterceptor {

    private static final String RATE_LIMIT_JSON =
            "{\"code\":\"RATE_LIMITED\",\"message\":\"请求过于频繁，请稍后再试\"}";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String resource = resolveResource(request);
        if (resource == null) {
            return true; // 非 API 路径（静态资源、actuator 等），不限流
        }

        Entry entry = null;
        try {
            entry = SphU.entry(resource);
            return true; // 放行：未触发限流
        } catch (BlockException ex) {
            // 触发限流：返回 429 Too Many Requests
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(RATE_LIMIT_JSON);
            return false; // 中断请求处理
        } finally {
            // 必须调用 exit()，否则 Sentinel 内部线程计数不会递减，
            // 累积后会导致后续请求全部被误判为限流
            if (entry != null) {
                entry.exit();
            }
        }
    }

    /**
     * 根据请求路径和方法映射到 Sentinel 资源名。
     * 资源名与 SentinelConfig 中注册的 FlowRule.resource 对应。
     */
    private String resolveResource(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();

        // 精确匹配高频路径
        if ("/api/v1/posts".equals(path) && "GET".equals(method)) {
            return "api-posts-list";
        }
        if (path != null && path.matches("/api/v1/posts/[^/]+") && "GET".equals(method)) {
            return "api-post-detail";
        }
        if ("/api/v1/auth/code".equals(path) && "POST".equals(method)) {
            return "api-auth-code";
        }

        // 兜底：所有 /api/ 路径
        if (path != null && path.startsWith("/api/")) {
            return "api-default";
        }

        return null; // 非 API 路径，不限流
    }
}
