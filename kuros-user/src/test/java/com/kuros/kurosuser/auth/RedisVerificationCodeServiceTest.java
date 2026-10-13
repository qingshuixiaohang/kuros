package com.kuros.kurosuser.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证码服务的 dev 固定码行为（安全加固 sec-01，A1 漏洞的单元层验收）。
 *
 * 漏洞背景：{@code app.auth.dev-code} 曾是默认 123456 的配置，任何忘配 env 的
 * 部署都得到同一个万能登录码。修复 = 默认空 + 非 dev/test profile 显式注入时
 * 拒绝启动（见 DevCodeSafetyValidator）；本类守住第三道防线：**即使配置层被绕过**，
 * dev-code 为空时服务本身也不产生可预测的验证码。
 *
 * 为什么这一层要单独测（集成测试已覆盖登录链路）：
 * 三道防线的意义在于任一层失效时其余层仍有效。单元层用 mock Redis，毫秒级、
 * 无容器、进主套件——它证明的是"服务自身不生产万能码"，与集成层证明的
 * "接口不放行万能码"是两件不同的事。
 *
 * 为什么用 mock 而不是真 Redis：本类断言的是 issue() 的**产出码**与**写入 Redis
 * 的键值/TTL**，不涉及 Redis 的任何行为语义。真 Redis 下的键过期由
 * AuthLoginIntegrationTest（Testcontainers）覆盖，两层不重复。
 */
@ExtendWith(MockitoExtension.class)
class RedisVerificationCodeServiceTest {

    private static final String PHONE = "13800000001";
    private static final int EXPIRATION_SECONDS = 300;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private SmsSender smsSender;

    private RedisVerificationCodeService service;

    /** 每个用例前重建 service：构造器参数在各用例间不同（dev-code 空 vs 非空）。 */
    @BeforeEach
    void setUp() {
        stubOpsForValue();
    }

    private RedisVerificationCodeService serviceWith(String devCode, boolean devCodeExposed) {
        return new RedisVerificationCodeService(
                redisTemplate, smsSender, EXPIRATION_SECONDS, devCodeExposed, devCode);
    }

    /**
     * 通用 stub 改成按用例显式声明：MockitoExtension 默认 strict stubs 会把
     * "本用例没用到的桩"判为 UnnecessaryStubbing（test class 级失败）。
     * 频率限制标记的 hasKey 是可变状态（用例间 true/false 不同），放 setUp 里
     * 必然有一半用例用不到——所以只在需要的用例中 stub，用 lenient() 兜住
     * opsForValue（verify 系列断言即使没走到 opsForValue 也不该因此红）。
     */
    private void stubOpsForValue() {
        org.mockito.Mockito.lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void 未配置固定验证码时签发的是不可预测的随机码() {
        // A1 的核心断言：dev-code 空 → issue() 绝不能返回任何"想当然"的值。
        // 修复前（默认 123456）这里返回的是 "123456"，任何调用方都能背下它登录。
        service = serviceWith("", false);

        String code = service.issue(PHONE);

        assertThat(code)
                .as("未注入 dev-code 时不得产生固定验证码")
                .isNotEqualTo("123456")
                .hasSize(6)
                .containsOnlyDigits();
    }

    @Test
    void 未配置固定验证码时写入Redis的正是那个随机码() {
        // 上一只证明了"返回的不是 123456"，这一只证明"Redis 里存的与返回的一致"：
        // 否则登录端拿到的仍是服务内部另一个固定值（返回与存储不一致的隐患面）。
        service = serviceWith(null, false);

        String code = service.issue(PHONE);

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(eq("sms:code:" + PHONE), eq(code), ttl.capture());
        assertThat(ttl.getValue()).isEqualTo(Duration.ofSeconds(EXPIRATION_SECONDS));
    }

    @Test
    void 未配置固定验证码时频率限制生效且60秒内重复发送被拒() {
        // 修复前 dev 模式整段跳过频率限制（爆破无成本）；现在 devCode 为空 →
        // 限制必须真的在管。这是 A1 里"dev 模式下 60s 频率限制被整段跳过"的收口。
        service = serviceWith("", false);
        when(redisTemplate.hasKey("sms:limit:" + PHONE)).thenReturn(true);
        assertThatThrownBy(() -> service.issue(PHONE))
                .as("未配置 dev-code 时 60s 频率限制必须生效")
                .isInstanceOf(com.kuros.kurosuser.shared.exception.AuthRequestException.class)
                .hasMessageContaining("验证码发送过于频繁");

        // 被拒时不得再写验证码（否则限流形同虚设：写一次 Redis 就能继续试）
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void 配置固定验证码时仍签发该固定码并跳过频率限制() {
        // 便利路径不回归：compose/.env 显式注入 123456 的开发环境行为不变。
        // 注意这条与上一条互为镜像——同一个服务的两种配置形态，都不能被另一条破坏。
        service = serviceWith("123456", true);

        String code = service.issue(PHONE);

        assertThat(code).isEqualTo("123456");
        // dev 便利：不写 limit key（注释里的两个合法场景：compose-smoke 重启二次登录、
        // 前端演示反复登录登出）。生产 devCode 为空 → 走的是上面那条限制路径。
        verify(valueOperations).set(eq("sms:code:" + PHONE), eq("123456"), eq(Duration.ofSeconds(EXPIRATION_SECONDS)));
        verify(valueOperations, never()).set(eq("sms:limit:" + PHONE), anyString(), any(Duration.class));
    }

    @Test
    void 验证码不匹配时校验失败且不删除已存验证码() {
        // 安全语义：校验失败绝不能顺手删掉 Redis 里的真码（那会让攻击者
        // 用一次错误尝试就把合法用户的验证码清掉，形成拒绝服务）。
        service = serviceWith("", false);
        when(valueOperations.get("sms:code:" + PHONE)).thenReturn("654321");

        assertThat(service.verify(PHONE, "123456")).isFalse();
        verify(redisTemplate, never()).delete("sms:code:" + PHONE);
    }

    @Test
    void 验证码匹配时校验成功并立即删除() {
        service = serviceWith("", false);
        when(valueOperations.get("sms:code:" + PHONE)).thenReturn("654321");

        assertThat(service.verify(PHONE, "654321")).isTrue();
        verify(redisTemplate).delete("sms:code:" + PHONE);
    }

    @Test
    void 空白字符的固定验证码按未配置处理() {
        // APP_AUTH_DEV_CODE=" " 是"看着配了其实没配"的形态。判定口径必须与
        // DevCodeSafetyValidator 的 isBlank 一致，否则两侧一个放行一个拒绝。
        service = serviceWith("   ", true);

        String code = service.issue(PHONE);

        assertThat(code).isNotEqualTo("   ").hasSize(6).containsOnlyDigits();
        // 走的是生产路径：频率限制标记应当被写入
        verify(valueOperations).set(eq("sms:limit:" + PHONE), eq("1"), eq(Duration.ofSeconds(60)));
    }

    @Test
    void 生产路径会写入频率限制标记() {
        service = serviceWith("", false);

        service.issue(PHONE);

        verify(valueOperations).set(eq("sms:limit:" + PHONE), eq("1"), eq(Duration.ofSeconds(60)));
    }
}
