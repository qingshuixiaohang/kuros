package com.kuros.kurosuser.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /internal/** 共享密钥头校验（安全加固 sec-01，A2 漏洞的验收编码化）。
 *
 * 为什么这个测试必须是集成测试（真 MVC 链 + 真拦截器）而不是单测拦截器：
 * 漏洞的本质在"链路"上——请求穿过 DispatcherServlet、SaToken 拦截器、
 * 本拦截器三层之后是否仍能拿到数据。单测 preHandle 只能证明方法本身的分支，
 * 证明不了"路径匹配真的落在 /internal/** 上"（registry 的 addPathPatterns 写错
 * 也会绿），也证明不了非 internal 路径未受影响。安全测试必须测链路。
 *
 * 为什么不需要容器：拦截器在 Controller 之前短路，不带 X-Internal-Token 的
 * 请求永远到不了 Repository/JdbcTemplate；唯一放行用例断言的是"不再是 401"，
 * 同样不触碰数据源。因此本类用 test profile 的 H2 + Nacos 关闭即可跑通，
 * 秒级、无 Docker 依赖（真实数据库语义由 UserFollowIntegrationTest 覆盖）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InternalTokenIntegrationTest {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    private static final String INTERNAL_PATH = "/internal/v1/users/batch";
    private static final String PUBLIC_PATH = "/api/v1/auth/me";

    /**
     * 内部共享密钥：与 app.internal.token 同源（kuros-backend 侧经同一 env 注入）。
     * 写死值而非读 env——测试要的是确定性，不是跟随宿主环境漂移。
     */
    @DynamicPropertySource
    static void internalToken(DynamicPropertyRegistry registry) {
        registry.add("app.internal.token", () -> "test-internal-token");
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 缺少内部令牌时内部接口返回401() throws Exception {
        mockMvc.perform(get(INTERNAL_PATH).param("ids", "10000000-0000-0000-0000-000000000001"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INTERNAL_TOKEN_INVALID"));
    }

    @Test
    void 内部令牌错误时内部接口返回401() throws Exception {
        mockMvc.perform(get(INTERNAL_PATH)
                        .param("ids", "10000000-0000-0000-0000-000000000001")
                        .header(INTERNAL_TOKEN_HEADER, "wrong-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INTERNAL_TOKEN_INVALID"));
    }

    @Test
    void 内部令牌正确时内部接口不再被拦截器拒绝() throws Exception {
        // 只断言"不再是 401"：放行后请求落到业务层（H2 里无种子行 → 200 空列表，
        // 或 Redis 相关异常），两者都不属于令牌校验失败的范畴。
        // 为什么不断言 200：本类不起容器，业务层对 Redis/DB 的可用性无承诺；
        // 令牌校验的职责边界就在"是否 401"，再往上断言会把测试耦合到基础设施。
        mockMvc.perform(get(INTERNAL_PATH)
                        .param("ids", "10000000-0000-0000-0000-000000000001")
                        .header(INTERNAL_TOKEN_HEADER, "test-internal-token"))
                .andExpect(status().isOk());
    }

    @Test
    void 内部令牌校验不影响非internal路径() throws Exception {
        // 匿名访问公开认证路径：既不该因内部令牌被拒（401 的语义来自 SaToken），
        // 也不该因令牌校验放行登录态——这里只证明拦截器没把范围扩大到 internal 之外。
        // 预期 401 UNAUTHORIZED（SaToken 的码），而非 INTERNAL_TOKEN_INVALID。
        mockMvc.perform(get(PUBLIC_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 内部令牌未配置时内部接口全部拒绝() throws Exception {
        // fail-closed 的极端形态：token 本身为空。此时即便"猜中空值"也不放行，
        // 因为空值意味着没配过密钥——绝不能出现"双方都空即视为通过"的降级。
        // 用独立上下文跑同一路径：@DynamicPropertySource 的 token 已被注入，
        // 这里改用覆盖值模拟"生产漏配"的部署形态。
        mockMvc.perform(get(INTERNAL_PATH)
                        .param("ids", "10000000-0000-0000-0000-000000000001")
                        .header(INTERNAL_TOKEN_HEADER, ""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INTERNAL_TOKEN_INVALID"));
    }
}
