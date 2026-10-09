package com.kuros.kurosbackend.user.client;

import feign.RequestInterceptor;
import feign.RequestTemplate;

/**
 * kuros-user 内部 API 的共享密钥注入（安全加固 sec-01，A2 漏洞的调用方侧）。
 *
 * 漏洞背景：kuros-user 的 /internal/** 此前零鉴权，compose 又把 user 服务端口
 * 发布到宿主机 0.0.0.0——等于这台机器网络上任何人都能批量导出全站用户与关注关系。
 * 修复后 user 侧要求 {@code X-Internal-Token} 头（见 kuros-user 的
 * InternalTokenInterceptor），本类就是调用方必须配套的那一半：
 * 只堵服务端不补客户端，Feign 调用会全部 401，内部链路直接断。
 *
 * 为什么用请求头而不是 @RequestHeader 方法参数：
 * 1. 头是调用方的凭证而非业务参数，混进方法签名会让"契约"与"安全机制"纠缠；
 * 2. 本项目只有一个 Feign 客户端（UserDirectoryClient，见 @EnableFeignClients），
 *    统一注入与"只给这一个客户端加"等价，且未来新增内部客户端自动继承同一机制；
 * 3. 密钥从同一处（app.internal.token）读取，不存在两处各配一份然后漂移的可能。
 *
 * 注意：本类**不能**只标 @Component 就指望生效——Spring Cloud OpenFeign 不收集
 * 父上下文里的 RequestInterceptor bean，必须经 {@link InternalTokenFeignConfig}
 * 的 FeignClientConfigurer 注册进客户端子上下文（该文件注释里有实测记录）。
 */
public class InternalTokenRequestInterceptor implements RequestInterceptor {

    /**
     * 与 kuros-user 的 InternalTokenInterceptor 常量逐字一致：
     * 两侧各有一份定义（跨服务无法共享常量类），改名必须同步——
     * 这是该机制唯一的脆弱点，故用注释显式标记契约关系。
     */
    static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final String internalToken;

    public InternalTokenRequestInterceptor(String internalToken) {
        this.internalToken = internalToken;
    }

    @Override
    public void apply(RequestTemplate template) {
        // 未配置密钥时**不加头**（而不是加空头）：
        // 服务端对"无头"与"空头"的响应都是 401（fail-closed），
        // 但不加头让请求形态保持诚实——调用方确实没有凭证，
        // 排查时 feign 日志里不会出现一个具有欺骗性的空值头。
        if (internalToken == null || internalToken.isBlank()) {
            return;
        }
        template.header(INTERNAL_TOKEN_HEADER, internalToken);
    }
}
