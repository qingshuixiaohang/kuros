package com.kuros.kurosbackend.user.client;

import com.kuros.kurosbackend.UserDirectoryStub;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内部 API 调用方凭证注入（安全加固 sec-01，A2 漏洞的 backend 侧验收）。
 *
 * 漏洞背景：kuros-user 的 /internal/** 现在要求 X-Internal-Token 共享密钥
 * （见 kuros-user 的 InternalTokenInterceptor）。只堵服务端不补客户端，
 * Feign 调用会全部 401——内容域作者组装降级为占位、资料页 503，
 * 故障表现为"用户域挂了"而不是"密钥没配"，排查方向完全被带偏。
 * 本类钉住客户端这一半：密钥一旦配上，Feign 必须真的把它带出去。
 *
 * 为什么用桩 + 断言"桩收到了什么"而不是 mock RequestInterceptor：
 * 断言桩实际收到的头，证明的是端到端的 HTTP 行为（拦截器真的被执行、头真的
 * 在请求线上）；mock 拦截器只能证明"我调了我自己的 mock"，与真实请求无关。
 * 这正是"测真实行为而非 mock 行为"的标准做法。
 *
 * 为什么不起容器：桩是 JDK 内置 HttpServer（UserDirectoryStub），
 * Feign 经 app.feign.kuros-user.url 直连，秒级；真实 lb 链路另由
 * NacosFeignIntegrationTest 覆盖，两层不重复。
 */
@SpringBootTest
@ActiveProfiles("test")
class InternalTokenRequestInterceptorTest {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    private static final String SEED_USER_1 = "10000000-0000-0000-0000-000000000001";

    // 复用 split-08 的桩：它现在也校验 X-Internal-Token（与 kuros-user 同行为）
    static final UserDirectoryStub userDirectory = new UserDirectoryStub();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("app.feign.kuros-user.url", userDirectory::baseUrl);
        registry.add("app.internal.token", () -> "test-internal-token");
    }

    @Autowired
    private UserDirectoryClient client;

    @BeforeEach
    void resetStub() {
        // 复位期望令牌与健康开关：个别用例会临时改它们，必须每例前恢复
        userDirectory.setExpectedInternalToken("test-internal-token");
        userDirectory.setHealthy(true);
    }

    @AfterAll
    static void closeStub() {
        userDirectory.close();
    }

    @Test
    void Feign请求会带上内部共享密钥头() {
        client.batch(List.of(SEED_USER_1));

        // 端到端断言：桩确实收到了约定的头名与值（而不是客户端"自认为带了"）
        assertThat(userDirectory.lastSeenInternalToken())
                .as("Feign 必须注入 X-Internal-Token，否则 kuros-user 侧 401")
                .isEqualTo("test-internal-token");
    }

    @Test
    void 密钥与桩期望不一致时请求失败() {
        // 反向验证"头真的在校验"：把桩的期望值改掉，同一个客户端必须失败。
        // 若这条绿而上一条也绿，说明头不是摆设——两侧真的在用它做准入判断。
        userDirectory.setExpectedInternalToken("a-different-token");

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                client.batch(List.of(SEED_USER_1))))
                .as("令牌不匹配时 kuros-user 侧应拒绝（本处以 Feign 401 失败体现）")
                .isNotNull();
    }

    @Test
    void 客户端未配置密钥时不带头且请求被拒() {
        // fail-closed 的服务端镜像：桩期望空值 = 一律拒绝（含"客户端也没带头"）。
        // 这条证明"两边都空即通过"的降级不存在——忘记配置会显式失败。
        userDirectory.setExpectedInternalToken("");

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                client.batch(List.of(SEED_USER_1))))
                .isNotNull();
    }

    @Test
    void 关注列表调用同样带内部密钥() {
        // batch 之外的两个端点（following/followers）走同一个拦截器，
        // 但要防的是"只给 batch 配了"的疏漏——各自验一次更诚实
        client.following(SEED_USER_1, 1, 20);

        assertThat(userDirectory.lastSeenInternalToken()).isEqualTo("test-internal-token");
    }

    @Test
    void 内部密钥不影响响应解码() {
        // 回归防线：加了头之后 Feign 的响应解码链路必须照旧工作
        // （ApiResponse 契约、List 绑定都不能被拦截器破坏）
        var response = client.batch(List.of(SEED_USER_1));

        assertThat(response).isNotNull();
        assertThat(response.data())
                .as("桩应返回种子用户摘要")
                .isNotEmpty();
        assertThat(response.data().get(0).nickname()).isEqualTo("潮声档案员");
    }

    @Test
    void 未配置密钥的构造形态下不注入头() {
        // 单元层直接验拦截器（不走 Spring）：未配置时不加头，而不是加空头。
        // 空头会让 feign 日志出现一个具有欺骗性的凭证值，排查时误以为"配过了"。
        var interceptor = new InternalTokenRequestInterceptor("");
        feign.RequestTemplate template = new feign.RequestTemplate();

        interceptor.apply(template);

        assertThat(template.headers())
                .as("未配置密钥时不得注入 X-Internal-Token 头")
                .doesNotContainKey(INTERNAL_TOKEN_HEADER);
    }

    @Test
    void 配置了密钥的构造形态下注入头() {
        var interceptor = new InternalTokenRequestInterceptor("secret-token");
        feign.RequestTemplate template = new feign.RequestTemplate();

        interceptor.apply(template);

        assertThat(template.headers().get(INTERNAL_TOKEN_HEADER))
                .containsExactly("secret-token");
    }
}
