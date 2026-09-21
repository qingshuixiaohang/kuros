package com.kuros.kurosbackend.config;

import com.kuros.kurosbackend.storage.LocalStorageStrategy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 静态资源映射配置。
 *
 * 为什么只在本地模式下注册 ResourceHandler？
 * 因为本地模式下文件在磁盘上，Spring MVC 可以直接映射目录（零性能开销）。
 * MinIO 模式下文件在对象存储里，本地没有文件，需要由 MediaResourceController 代理。
 *
 * 为什么用 @ConditionalOnProperty 而不是 @ConditionalOnBean？
 * @ConditionalOnBean 依赖 Bean 注册顺序（组件扫描时 config 包可能在 storage 包之前处理），
 * 导致条件求值时 LocalStorageStrategy 还未注册，条件判定为 false，/media/** 返回 404。
 * @ConditionalOnProperty 直接读配置值，不依赖 Bean 注册顺序，行为可预测。
 */
@Configuration
@ConditionalOnProperty(name = "app.storage.type", havingValue = "local", matchIfMissing = true)
public class MediaResourceConfig implements WebMvcConfigurer {

    private final LocalStorageStrategy localStorageStrategy;

    // 只有本地模式下才需要注册 ResourceHandler。
    // 如果当前是 MinIO 策略，LocalStorageStrategy 不存在，这个 Bean 不会被创建。
    public MediaResourceConfig(LocalStorageStrategy localStorageStrategy) {
        this.localStorageStrategy = localStorageStrategy;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/media/**")
                .addResourceLocations(localStorageStrategy.root().toUri().toString());
    }
}
