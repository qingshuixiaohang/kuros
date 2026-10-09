package com.kuros.kurosuser.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 未注入 dev 固定验证码时的登录防线（安全加固 sec-01，A1 漏洞的验收编码化）。
 *
 * 漏洞背景：app.auth.dev-code 默认 123456 且响应回显 devCode，任何忘配 env 的
 * 部署（裸跑、IDEA 直跑）都等于把"任意手机号可登录"的钥匙公开挂在门口。
 *
 * 为什么用集成测试而不是只靠 RedisVerificationCodeService 单测：
 * 单测证明"devCode 空时 issue() 返回随机码"，但证明不了登录接口的**可观测行为**
 * ——调用方拿到 4xx 而不是 200。漏洞的危害在接口边界上，测试就必须在接口边界上
 * 断言（这正是"测试行为而非实现"）。
 *
 * 为什么需要 Redis（故与 AuthLoginIntegrationTest 同属容器套件）：
 * 验证码的存储介质就是 Redis，verify() 必须真的去读一次才能判定"猜的码不中"。
 * mock Redis 只能证明代码分支，证明不了"Redis 里躺着真码时猜码者被拒"这个
 * 真实场景——而那正是漏洞的复现路径。
 *
 * 为什么直写 Redis 而不调 /auth/code：本类要验证的世界里没有万能码，
 * /auth/code 会生成一个真随机码并写进 Redis，测试无从得知它的值。
 * 直写 Redis 等价于"短信真的发出去了"这个外部事件，随后用 123456 试登录——
 * 这恰是攻击者的动作（猜万能码）。拦截点因此是真实链路而非 mock。
 *
 * 与 AuthLoginIntegrationTest 的分工：那个类用 @DynamicPropertySource 显式注入
 * 123456 验证"dev 便利路径不回归"，本类验证"默认（不注入）时防线生效"。
 * 两者是同一枚硬币的两面，缺一则漏洞可能从任一侧复发。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DevCodeDefaultIntegrationTest {

    private static final String PHONE = "13800000031";
    private static final String REAL_CODE = "654321";

    /**
     * 显式覆盖 test profile 的 123456：本类要验证的正是"没有 dev-code 的世界"。
     * 为什么必须覆盖：application-test.properties 为了让登录类测试能跑而注入了
     * 固定码；不覆盖就等于什么都没测。空字符串 = 未注入（与生产漏配等价）。
     */
    @DynamicPropertySource
    static void withoutDevCode(DynamicPropertyRegistry registry) {
        registry.add("app.auth.dev-code", () -> "");
        registry.add("app.auth.dev-code-exposed", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void seedRealCode() {
        // 直写 Redis 模拟"真实验证码已发送"：绕过 /auth/code（它在无万能码时
        // 生成不可预测的随机码，测试拿不到值）。key 前缀与 RedisVerificationCodeService
        // 的 CODE_KEY_PREFIX 逐字一致——这是测试对该实现的唯一耦合点，
        // 若前缀变更，此处的失败会显式提示（而不是静默放过猜码攻击）。
        redisTemplate.opsForValue().set("sms:code:" + PHONE, REAL_CODE, Duration.ofSeconds(300));
    }

    @Test
    void 未注入固定验证码时不能凭默认码登录() throws Exception {
        // 旧行为：/code 返回 200 且 data.devCode = "123456"，随后同码登录 200。
        // 修复后：用万能码登录必须 400——Redis 里躺着的真码是 654321，123456 猜不中。
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"" + PHONE + "\",\"code\":\"123456\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_CODE"));
    }

    @Test
    void 未注入固定验证码时正确验证码仍能登录() throws Exception {
        // 反向验证"防线没把合法用户锁死"：Redis 里的真码照旧可用。
        // 这条与上一条共同证明：变的不是"能不能登录"，而是"能不能靠猜万能码登录"。
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"" + PHONE + "\",\"code\":\"" + REAL_CODE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("漂泊者0031"));
    }

    @Test
    void 未注入固定验证码时响应体不回显验证码() throws Exception {
        // 回显开关也要默认关闭：响应体带 devCode 等于把登录码印在接口上。
        // /auth/code 会用随机码覆盖 Redis 里的值，故本用例不依赖 @BeforeEach 的种子
        // （它只影响前两条登录用例），单独调一次即可断言响应形态。
        mockMvc.perform(post("/api/v1/auth/code")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"" + PHONE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.devCode").doesNotExist());
    }
}
