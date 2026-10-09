package com.kuros.kurosbackend.user.client;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * kuros-user 内部 API 调用方的凭证装配（安全加固 sec-01，A2 漏洞的客户端侧）。
 *
 * 为什么需要这个类（而不是只写一个 @Component RequestInterceptor）：
 * Spring Cloud OpenFeign 不会自动把父上下文里的 RequestInterceptor bean 装配给
 * Feign 客户端——它只从**客户端自己的子上下文**（由 @EnableFeignClients 的
 * defaultConfiguration / client 级 configuration 提供）里收集拦截器。
 * 这是实测结论：仅标 @Component 时，bean 确实存在于容器中
 * （getBeanNamesForType 能查到），但 Feign 发出的请求并不携带该头，
 * 内部调用全部 401，而症状表现为"用户域不可用"（走 UserDirectoryFacade 的降级
 * 路径），极易误判为服务宕机——安全机制静默失效比没有更危险。
 *
 * 为什么用 defaultConfiguration 而不是 FeignClientConfigurer：
 * 本项目用的 spring-cloud-openfeign 5.0.1 里，FeignClientConfigurer 只剩
 * primary()/inheritParentConfiguration() 两个开关（4.x 起不再提供
 * addRequestInterceptor 之类的注册入口，注册改由 defaultConfiguration 承担）。
 * 按 3.x 时代的 addRequestInterceptor 写法直接编译不过——包名与 API 都换了。
 *
 * 为什么配置类放在 user.client 包而不是 shared/config：它只服务于内部调用契约，
 * 与 UserDirectoryClient 同包同生命周期；放进 shared/config 会让"这是通用机制"
 * 的错觉扩散到未来无关的 Feign 客户端上。
 */
@Configuration
public class InternalTokenFeignConfig {

    /**
     * 用 Bean 方法而非字段注入：密钥在构造期一次性解析，运行中改配置不会产生
     * "同一个实例两种行为"的错觉。
     */
    @Bean
    public InternalTokenRequestInterceptor internalTokenRequestInterceptor(
            @Value("${app.internal.token:}") String internalToken) {
        return new InternalTokenRequestInterceptor(internalToken);
    }

    /**
     * Feign 只认子上下文里的 RequestInterceptor bean，这里显式声明它。
     * 为什么必须是 @Bean 而不能靠组件扫描：本类被 @EnableFeignClients 的
     * defaultConfiguration 引用时会作为配置类注册进客户端的子上下文，
     * 子上下文不继承父上下文的组件扫描结果——不写这个 @Bean 就等于没配。
     */
    @Bean
    public RequestInterceptor internalTokenHeaderInjector(InternalTokenRequestInterceptor interceptor) {
        return interceptor;
    }
}
