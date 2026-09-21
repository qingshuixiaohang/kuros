package com.kuros.kurosbackend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SpringDoc 开关的动态刷新桥接（切片 #8 / nacos-02）。
 *
 * 为什么需要桥接？springdoc 自动配置在启动期一次性读取 springdoc.api-docs.enabled，
 * 配置中心改值对 springdoc 本身无效（要重启才生效）。
 * 桥接 bean 挂 @RefreshScope：refresh 事件清缓存 → 下次访问时用最新 Environment 重建，
 * 于是"当前生效的开关状态"可以被外部观察到（lazy 路径）。
 *
 * 与 SentinelRuleRefresher 构成对照：
 * - eager（事件监听重读 Environment）：改完立即生效，适合规则类
 * - lazy（@RefreshScope 代理重建）：下次访问才见新值，适合状态观察类
 */
@Configuration
public class SpringDocStatusBridge {

    @Bean
    @RefreshScope
    public ApiDocsStatus apiDocsStatus() {
        return new ApiDocsStatus();
    }

    public static class ApiDocsStatus {

        @Value("${springdoc.api-docs.enabled:true}")
        private boolean apiDocsEnabled;

        public boolean isApiDocsEnabled() {
            return apiDocsEnabled;
        }
    }
}
