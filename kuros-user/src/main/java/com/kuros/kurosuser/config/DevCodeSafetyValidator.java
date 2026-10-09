package com.kuros.kurosuser.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * dev 固定验证码的启动期安全校验（安全加固 sec-01，A1 漏洞的 fail-fast 部分）。
 *
 * 漏洞背景：{@code app.auth.dev-code} 曾是默认 123456 的"便利配置"，
 * 任何忘配 env 的部署都拿到同一个万能登录码。把默认值改成空解决了"默认送钥匙"，
 * 但还有一种更隐蔽的形态：某处**显式**注入了 dev-code（改配置残留、运维误配、
 * CI 环境变量串味），而 profile 又不是 dev/test——此时万能钥匙确实在运行，
 * 而且没人会注意到。这种部署必须在启动那一刻就失败，而不是等被刷号才发现。
 *
 * 为什么用校验而不是 @ConditionalOnProperty：
 * @ConditionalOnProperty 表达的是"有这个配置就注册 bean"，语义是**特性开关**；
 * 这里要表达的是"配置组合非法，拒绝启动"，是**校验**。用条件注解实现校验会把
 * 错误变成"验证码服务悄悄消失"——登录链路静默坏掉，比拒绝启动更难排查。
 *
 * 为什么用 ContextRefreshedEvent 而不是 ApplicationStartedEvent：
 * 两个事件在真实 Boot 启动里都会发，但前者更早（finishRefresh 阶段，WebServer
 * 尚未监听端口、Nacos 注册尚未发生），失败窗口更小；且它是 Spring 核心事件、
 * 不依赖 SpringApplication 的发布逻辑——ApplicationContextRunner 之类的测试
 * 装配器同样会触发它，fail-fast 因此可被无容器测试覆盖（毫秒级、进主套件）。
 *
 * 为什么 fail 是抛异常而不是打日志：日志告警在单人项目里等于没有——没人盯着；
 * 而拒绝启动会在部署流水线上立刻显红。安全配置的默认行为必须是"阻断"。
 */
@Configuration
public class DevCodeSafetyValidator {

    /**
     * 允许 dev-code 生效的 profile 白名单。
     * - dev：开发机主动声明的开发环境
     * - test：测试套件靠固定码走完整登录链路（AuthLoginIntegrationTest 的夹具），
     *   漏掉它会让全套件红，反而诱导后人把整个校验删掉——所以白名单必须含它
     * 其余一律拒绝（含"没设 profile"的默认形态，那正是裸跑/IDEA 直跑的形态）
     */
    private static final Set<String> TRUSTED_PROFILES = Set.of("dev", "test");

    private final String devCode;
    private final Set<String> activeProfiles;

    public DevCodeSafetyValidator(
            @Value("${app.auth.dev-code:}") String devCode,
            Environment environment) {
        this.devCode = devCode;
        // 从 Environment 读活跃 profile 而不是 @Value("${spring.profiles.active:}")：
        // 后者的取值来源只是那个属性本身，而测试用 @ActiveProfiles("test") 设的 profile
        // 只写进 Environment、不写回该属性——实测 @SpringBootTest 下它是空数组，
        // 于是"test profile 也 fail-fast"，把测试套件整个锁死（安全校验反成事故源）。
        // getActiveProfiles() 是 profile 的权威来源：env 属性、--spring.profiles.active、
        // spring.profiles.active 配置、@ActiveProfiles 最终都汇聚到它这里。
        this.activeProfiles = Arrays.stream(environment.getActiveProfiles())
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    @EventListener(ContextRefreshedEvent.class)
    public void rejectDevCodeOutsideTrustedProfiles() {
        // isBlank 而非 isEmpty：APP_AUTH_DEV_CODE=" " 是"看着配了其实没配"的形态。
        // 与 RedisVerificationCodeService 的 devMode = !devCode.isBlank() 必须同一口径，
        // 否则这种部署会被一条永不生效的校验拒绝启动（或反过来被放过）。
        if (devCode == null || devCode.isBlank()) {
            // 未注入万能码 = 最安全的形态，任何 profile 都放行
            return;
        }
        if (activeProfiles.stream().anyMatch(TRUSTED_PROFILES::contains)) {
            return;
        }
        // 消息里带上当前 profile 与修法：运维看到这行就知道该删哪个 env 或切哪个
        // profile，而不是去翻源码猜"到底是哪条校验失败了"（fail-fast 的价值一半在
        // 错误信息——说不清怎么修的拒绝启动只会被后人用 try/catch 包起来绕过）
        throw new IllegalStateException(
                "app.auth.dev-code 只在 dev/test profile 下允许配置（当前 profile=" + activeProfiles
                        + "）。固定验证码让任意手机号可登录，只允许在可信开发环境显式注入；"
                        + "请清空 APP_AUTH_DEV_CODE 或改用 dev/test profile 启动");
    }
}
