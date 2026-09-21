package com.kuros.kurosuser.shared.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS 配置（split-06 自 kuros-backend 迁入，逐字一致）。
 *
 * 为什么用户服务也要配 CORS：浏览器的跨域判定发生在"前端源 vs 网关源"，
 * 网关只做透传不改写响应头，CORS 响应头最终由服务的 CorsConfig 给出——
 * auth 端点在拆分后由本服务应答，缺了这份配置前端登录会直接被浏览器拦下。
 *
 * 从原来 SecurityConfig 里的 CorsConfigurationSource 独立出来。
 * 为什么不再放在 Security 里？因为我们已经完全移除了 Spring Security，
 * CORS 是 HTTP 层面的跨域策略，跟认证无关，用 Spring MVC 的 WebMvcConfigurer 配置更简洁。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${app.cors.allowed-origin:http://localhost:3000}")
    private String allowedOrigin;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigin)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "X-XSRF-TOKEN")
                .exposedHeaders("X-XSRF-TOKEN")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
