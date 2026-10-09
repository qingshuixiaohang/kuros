package com.kuros.kurosuser.config;

import com.kuros.kurosuser.KurosUserApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * dev 固定验证码的启动期 fail-fast（安全加固 sec-01，A1 漏洞的验收编码化）。
 *
 * 漏洞背景：app.auth.dev-code 默认 123456。任何忘配 env 的部署——裸跑 jar、
 * IDEA 直跑、新人 clone 后 Start——都得到同一个万能验证码，等于任意手机号
 * 可登录。默认值改成空只是把"默认送钥匙"变成"默认没钥匙"，但**显式注入**
 * dev-code 的形态仍存在于非 dev 部署里（改配置残留、运维误配、CI 环境串味），
 * 那种情况必须启动即失败，而不是等到被刷号才发现。
 *
 * 为什么用 ApplicationContextRunner 而不是 @SpringBootTest：
 * 断言的是"启动失败"这一个行为，@SpringBootTest 会因上下文起不来而报 ERROR
 * 而不是可读的失败原因，且它需要 test profile 的 H2/Redis 环境。Runner 只装配
 * 配置类本身，毫秒级、无容器、失败信息还能被精确断言。
 *
 * 为什么要 withPropertyValues 关掉 compatibility-verifier：项目 POM 显式关掉了它
 * （application.properties 里有同款开关），但 Runner 不走 properties 文件，
 * 少了这行会在版本校验器上失败——那是环境噪声，与本次断言无关。
 */
class DevCodeFailFastIntegrationTest {

    /**
     * 三个放行 profile：dev/test/容器调试。它们的共同点是"本服务明确知道自己在
     * 一个可信环境里运行"——开发机上裸跑、IDEA 直跑、compose 的 user 服务都算。
     * 为什么 test 必须在列：测试套件靠 123456 走完整登录链路（AuthLoginIntegrationTest
     * 的夹具），把 test 漏掉的后果是全套件红，反而会诱导后人把校验整个删掉。
     */
    private static final String[] TRUSTED_PROFILES = {"dev", "test"};

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(KurosUserApplication.class)
            .withPropertyValues("spring.cloud.compatibility-verifier.enabled=false");

    @Test
    void 非可信profile注入固定验证码时启动失败() {
        runner.withPropertyValues(
                        "app.auth.dev-code=123456",
                        "app.auth.dev-code-exposed=true")
                .run(context -> {
                    // 双重断言：① 上下文没起来（fail-fast 而非告警后继续）
                    // ② 失败原因确实是配置校验，而不是装配顺序里别的偶发问题
                    org.assertj.core.api.Assertions.assertThat(context).hasFailed();
                    org.assertj.core.api.Assertions.assertThat(context.getStartupFailure())
                            .hasMessageContaining("app.auth.dev-code 只在 dev/test profile 下允许配置");
                });
    }

    @Test
    void 未注入固定验证码时任何profile都能启动() {
        // 默认值的世界（dev-code 空）：没有万能钥匙，也就没有"必须失败"的理由。
        // 这条防止后人写出"只要没有 dev profile 就一律拒绝启动"的过严实现。
        runner.withPropertyValues("app.auth.dev-code=")
                .run(context -> org.assertj.core.api.Assertions.assertThat(context).hasNotFailed());
    }

    @Test
    void devProfile注入固定验证码时正常启动() {
        runner.withPropertyValues("spring.profiles.active=dev", "app.auth.dev-code=123456")
                .run(context -> org.assertj.core.api.Assertions.assertThat(context).hasNotFailed());
    }

    @Test
    void testProfile注入固定验证码时正常启动() {
        // 不回归的红线：测试套件的登录夹具依赖它（见 AuthLoginIntegrationTest）
        runner.withPropertyValues("spring.profiles.active=test", "app.auth.dev-code=123456")
                .run(context -> org.assertj.core.api.Assertions.assertThat(context).hasNotFailed());
    }

    @Test
    void 仅空白字符的固定验证码不触发failFast() {
        // 空白视为"未注入"：.isBlank() 在 RedisVerificationCodeService 里也是同样的
        // 判定（devMode = !devCode.isBlank()），两侧口径不一致会让"看着配了其实没配"
        // 的部署（APP_AUTH_DEV_CODE=" "）被一条永不生效的校验拒绝启动。
        runner.withPropertyValues("app.auth.dev-code=   ")
                .run(context -> org.assertj.core.api.Assertions.assertThat(context).hasNotFailed());
    }
}
