package com.kuros.kurosuser.shared.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaHttpMethod;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * SaToken 路由拦截配置（split-06 自 kuros-backend 迁入的按服务子集）。
 * 替代原来的 Spring Security SecurityConfig。
 *
 * 为什么用 SaInterceptor 而不是 Spring Security 过滤器链？
 * SaToken 的拦截器基于 Spring MVC Interceptor，比 Servlet Filter 更轻量，
 * 且天然支持注解鉴权（@SaCheckLogin、@SaCheckRole、@SaCheckPermission）。
 * 路由规则集中在这里，拆微服务后每个服务只需复制自己的路由子集——
 * 本文件就是 user 服务的那份子集：
 * - 去掉 Sentinel 限流拦截器（user 白名单不含 /api/v1/posts，Q15-A 不引入 Sentinel）
 * - 去掉 Swagger 白名单（user 不引入 springdoc）
 * - 去掉 /api/v1/admin/** 的 checkRole 规则（管理员端点仍属 backend 路由表）
 * - 白名单只保留探测与指标端点（内容域路径归 backend 自己的拦截器）
 */
@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {

    private final CsrfInterceptor csrfInterceptor;

    public SaTokenConfigure(CsrfInterceptor csrfInterceptor) {
        this.csrfInterceptor = csrfInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
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
            // 必须限定 GET：若只按路径豁免，匿名写操作也会跳过登录检查。
            // 白名单为什么只有两个端点：
            // - /actuator/health：容器 healthcheck 不带会话 Cookie，必须匿名可达
            // - /actuator/prometheus：指标采集器同样匿名，且 split-05 验收时已是 200，
            //   保持行为不回退（backend 不暴露此路径的无鉴权访问是它的选择，user 服务刻意放宽）
            SaRouter.match(SaHttpMethod.GET).match(
                    "/actuator/health",        // 容器健康检查
                    "/actuator/prometheus"     // Prometheus 指标采集
            ).stop();

            // 其余所有路由要求登录（auth 端点除外）——对齐旧版 anyRequest().authenticated()
            SaRouter.match("/**")
                    .notMatch("/api/v1/auth/**")
                    .check(r -> StpUtil.checkLogin());
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
