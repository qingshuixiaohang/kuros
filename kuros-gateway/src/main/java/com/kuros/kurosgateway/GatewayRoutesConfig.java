package com.kuros.kurosgateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 网关路由定义（切片 #9 / Q2 决策：显式路由 + 路径原样透传）。
 *
 * 为什么用编程式 DSL 而不是 application.properties 的
 * spring.cloud.gateway.server.webflux.routes[0].predicates[0] 索引写法：
 * 官方文档的标准写法在 Boot 4.1 + SCG 4.3 组合下实测绑定失败——
 * predicates[0] 的 "Path=/**" 标量值无法绑定到 PredicateDefinition，
 * 列表留空触发 @NotEmpty 校验直接拒绝启动（CI 首轮抓出）。
 * 编程式 DSL 不依赖 properties 索引 + 构造器转换链，且 uri 可经
 * 属性覆盖（集成测试用桩地址替换 lb:// 目标）。
 *
 * 纯换门（Q6 决策）：无任何改写过滤器；split-07 起共三条路由——
 * 认证前缀路由（/api/v1/auth/** → kuros-user）+ 关注端点路由
 * （/api/v1/users/{targetUserId}/follow → kuros-user）+ 兜底通配路由（/** → kuros-backend）。
 * 为什么关注路由用单段通配而不是前缀 /**：/api/v1/users/ 前缀下还有
 * 资料页与帖子列表等 backend 端点（窗口期降级 503），不能整段劫持到 user 服务
 * （那会把同样前缀的其他端点一起切走，破坏窗口期兼容）。
 */
@Configuration
public class GatewayRoutesConfig {

    @Bean
    public RouteLocator kurosRoutes(RouteLocatorBuilder builder,
            @Value("${app.routes.backend-uri:lb://kuros-backend}") String backendUri,
            @Value("${app.routes.user-uri:lb://kuros-user}") String userUri) {
        return builder.routes()
                // split-06：认证链路（/api/v1/auth/**）已迁至 kuros-user。
                // 优先级双重保障：① 声明在 backend 通配路由之前——DSL 不给路由
                // 分配 order，等 order 时匹配按稳定排序后的声明序取首个命中；
                // ② 显式 order(-1)，低于 backend 的默认 order(0)——让"认证路由
                // 先于 /** 匹配"成为显式配置语义，不依赖声明位置这一隐式约定
                .route("kuros-user-auth", r -> r.order(-1).path("/api/v1/auth/**").uri(userUri))
                // split-07：关注端点迁至 kuros-user，与认证路由同款选项。
                // 单段通配 * 只匹配一个路径段——不会误伤同前缀的资料页（/{id}）、
                // 帖子列表（/{id}/posts）与个人中心（/me/profile），它们仍走 backend 通配路由；
                // order(-1) 显式低于 backend 通配的默认 order(0)，优先级不依赖声明位置
                .route("kuros-user-follow", r -> r.order(-1).path("/api/v1/users/*/follow").uri(userUri))
                .route("kuros-backend", r -> r.path("/**").uri(backendUri))
                .build();
    }

}
