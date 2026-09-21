package com.kuros.kurosgateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 网关路由定义（切片 #9 / Q2 决策：显式单路由 + 路径原样透传）。
 *
 * 为什么用编程式 DSL 而不是 application.properties 的
 * spring.cloud.gateway.server.webflux.routes[0].predicates[0] 索引写法：
 * 官方文档的标准写法在 Boot 4.1 + SCG 4.3 组合下实测绑定失败——
 * predicates[0] 的 "Path=/**" 标量值无法绑定到 PredicateDefinition，
 * 列表留空触发 @NotEmpty 校验直接拒绝启动（CI 首轮抓出）。
 * 编程式 DSL 不依赖 properties 索引 + 构造器转换链，且 uri 可经
 * 属性覆盖（集成测试用桩地址替换 lb://kuros-backend）。
 *
 * 纯换门（Q6 决策）：只有这一条路由，无任何改写过滤器。
 */
@Configuration
public class GatewayRoutesConfig {

    @Bean
    public RouteLocator kurosRoutes(RouteLocatorBuilder builder,
            @Value("${app.routes.backend-uri:lb://kuros-backend}") String backendUri) {
        return builder.routes()
                .route("kuros-backend", r -> r.path("/**").uri(backendUri))
                .build();
    }

}
