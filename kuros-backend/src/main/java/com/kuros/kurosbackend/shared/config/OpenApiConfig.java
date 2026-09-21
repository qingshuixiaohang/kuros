package com.kuros.kurosbackend.shared.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文档配置。
 *
 * 为什么用 springdoc 而不是 springfox？
 * springfox 已停止维护（最后更新 2022），不支持 Jakarta EE（jakarta.servlet.*）。
 * springdoc 是当前社区主流，天然支持 Spring Boot 3/4 + Jakarta EE。
 *
 * 为什么在生产环境关闭 Swagger UI？
 * 1. 安全风险：暴露 API 结构给攻击者
 * 2. 性能开销：文档生成需要扫描所有 Controller
 * 3. 通过 springdoc.api-docs.enabled 环境变量控制
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("鸣潮社区 API")
                        .version("1.0.0")
                        .description("Kuros Web 鸣潮玩家社区后端 API 文档。" +
                                "包含社区帖子、用户资料、鉴权、评论等模块的完整接口。")
                        .contact(new Contact()
                                .name("Kuros Team")
                                .url("https://github.com/qingshuixiaohang/kuros")))
                // SaToken 使用 Cookie 鉴权，Swagger UI 需要知道 Cookie 名
                .addSecurityItem(new SecurityRequirement().addList("cookieAuth"))
                .components(new Components()
                        .addSecuritySchemes("cookieAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name("KUROS_SESSION")));
    }
}
