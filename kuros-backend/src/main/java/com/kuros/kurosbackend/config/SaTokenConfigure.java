package com.kuros.kurosbackend.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaHttpMethod;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * SaToken 路由拦截配置，替代原来的 Spring Security SecurityConfig。
 *
 * 为什么用 SaInterceptor 而不是 Spring Security 过滤器链？
 * SaToken 的拦截器基于 Spring MVC Interceptor，比 Servlet Filter 更轻量，
 * 且天然支持注解鉴权（@SaCheckLogin、@SaCheckRole、@SaCheckPermission）。
 * 路由规则集中在这里，拆微服务后每个服务只需复制自己的路由子集。
 */
@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {

    private final CsrfInterceptor csrfInterceptor;
    private final SentinelRateLimitInterceptor rateLimitInterceptor;

    public SaTokenConfigure(CsrfInterceptor csrfInterceptor,
                           ObjectProvider<SentinelRateLimitInterceptor> rateLimitInterceptor) {
        this.csrfInterceptor = csrfInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor.getIfAvailable();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Sentinel 限流拦截器（order -1，最先执行）：
        // 限流必须在鉴权之前——被限流的请求不应该浪费资源去做 Token 校验。
        // 当 app.sentinel.enabled=false 时 SentinelConfig 不加载，
        // ObjectProvider 返回 null，此处跳过注册
        if (rateLimitInterceptor != null) {
            registry.addInterceptor(rateLimitInterceptor)
                    .addPathPatterns("/api/**")
                    .order(-1);
        }

        // SaToken 鉴权拦截器（order 0，先于 CSRF 执行）。
        // 为什么鉴权在前、CSRF 在后？旧版 Spring Security 的 CSRF 忽略条件就是“匿名请求”
        // （ignoringRequestMatchers(request -> authentication == null || anonymous)），即：
        // 游客写操作先吃 401，只有已登录用户才轮到 CSRF 校验。
        // 若顺序反了（CSRF 在前），游客不带 Cookie 的写操作会得到 403 而非 401，破坏 API 契约
        registry.addInterceptor(new SaInterceptor(handle -> {
            // CORS 预检放行：addCorsMappings 是 MVC 级配置，预检 OPTIONS 依然会穿过拦截器链，
            // 而浏览器预检不携带 Cookie，不放行会被 checkLogin 挡下 401（MockMvc 测不出来，真实前端跨域必现）
            SaRouter.match(SaHttpMethod.OPTIONS).stop();

            // 公开 GET 只读路由：“方法 + 路径”双重匹配后 stop() 终止鉴权链放行。
            // 必须限定 GET：旧白名单就是 HttpMethod.GET 范围的（requestMatchers(HttpMethod.GET, ...)）；
            // 若只按路径豁免，游客 POST（如发评论）也会跳过登录检查
            SaRouter.match(SaHttpMethod.GET).match(
                    "/api/v1/posts/**",        // 帖子列表、详情、评论列表、互动状态
                    "/api/v1/users/*",         // 公开用户资料（单段通配，/users/me/profile 两段不在内，仍需登录）
                    "/api/v1/users/*/posts",   // 用户发布的帖子
                    "/api/v1/characters/**",   // 角色图鉴预留（PR #57 待合并，当前分支尚无此路由）
                    "/media/**",               // 上传图片的静态资源访问
                    "/actuator/health",        // 容器健康检查
                    "/swagger-ui/**",           // Swagger UI 静态资源
                    "/v3/api-docs/**"            // OpenAPI JSON 文档
            ).stop();

            // 其余所有路由要求登录（auth 端点除外）——对齐旧版 anyRequest().authenticated()
            SaRouter.match("/**")
                    .notMatch("/api/v1/auth/**")
                    .check(r -> StpUtil.checkLogin());

            // 管理员路由：额外要求 ADMIN 角色（对齐旧版 hasRole("ADMIN")）
            SaRouter.match("/api/v1/admin/**")
                    .check(r -> StpUtil.checkRole("ADMIN"));
        }))
                .addPathPatterns("/**")
                .order(0);

        // CSRF 双重提交 Cookie 拦截器（order 1，鉴权之后执行）：
        // 能走到这里 = 请求已登录（游客在上面已被 SaInterceptor 拦下 401），
        // 语义等价于旧版“仅对已认证用户做 CSRF 校验”；
        // 排除 /api/v1/auth/**：前端 api.ts 对 auth 路径不发 X-XSRF-TOKEN Header，与旧版一致
        registry.addInterceptor(csrfInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/v1/auth/**")
                .order(1);
    }
}
