package com.kuros.kurosuser.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 安全敏感配置默认值的静态校验（安全加固 sec-01，A1/A2 的默认值防线）。
 *
 * 为什么需要读文件而不是读 Environment：本类断言的正是"properties 文件里的默认值"
 * 本身——那是"忘配 env 的部署"（裸跑 jar、IDEA 直跑、新人 clone）唯一会看到的形态。
 * 读 Environment 测的是运行时解析后的值，而运行时值会被 env/compose 覆盖，
 * 恰恰会把这个漏洞的核心（默认值本身）从测试里掩盖掉。
 *
 * 为什么这不算"测实现"：properties 默认值是**部署契约**的一部分，与代码同等地
 * 决定安全姿态。它的回归形态很具体——有人为了本地方便把默认值改回 123456，
 * 所有运行时测试都会因为 env/compose 覆盖而继续绿，只有直接读文件能抓到。
 *
 * 与 DevCodeFailFastIntegrationTest 的分工：那个测"显式注入 + 非可信 profile"，
 * 本类测"什么都不注入时的兜底形态"。两个方向都必须有人守。
 */
class SecureDefaultsIntegrationTest {

    /**
     * kuros-user 的 application.properties：本类只读这一个文件。
     * 用相对仓库根的路径而非 classpath：target/classes 里的副本是构建产物，
     * 读它会绕开"源码被改但没重新构建"的情形（而那正是回归最常发生的形态）。
     */
    private static final Path MAIN_PROPERTIES =
            Paths.get("src", "main", "resources", "application.properties");

    private List<String> relevantLines() throws IOException {
        return Files.readAllLines(MAIN_PROPERTIES).stream()
                .map(String::trim)
                // 只保留未被注释掉的配置行：以 # 开头的是注释，不构成默认值
                .filter(line -> !line.startsWith("#"))
                .collect(Collectors.toList());
    }

    @Test
    void dev固定验证码的默认值必须是空() throws IOException {
        List<String> lines = relevantLines();

        assertThat(lines)
                .as("app.auth.dev-code 默认值必须是空（不注入万能码）")
                .anyMatch(line -> line.startsWith("app.auth.dev-code=")
                        && line.endsWith("=${APP_AUTH_DEV_CODE:}"));
    }

    @Test
    void dev验证码回显的默认值必须是关闭() throws IOException {
        List<String> lines = relevantLines();

        assertThat(lines)
                .as("app.auth.dev-code-exposed 默认值必须是 false（响应体不得回显验证码）")
                .anyMatch(line -> line.startsWith("app.auth.dev-code-exposed=")
                        && line.endsWith("=${APP_AUTH_DEV_CODE_EXPOSED:false}"));
    }

    @Test
    void 内部共享密钥的默认值必须是空() throws IOException {
        List<String> lines = relevantLines();

        // 默认空 = fail-closed：没配密钥的内部 API 全部 401（见 InternalTokenInterceptor）。
        // 若默认值改成某个"开发用密钥"，就等于把内部 API 的钥匙又印在了配置里。
        assertThat(lines)
                .as("app.internal.token 默认值必须是空（fail-closed）")
                .anyMatch(line -> line.startsWith("app.internal.token=")
                        && line.endsWith("=${APP_INTERNAL_TOKEN:}"));
    }

    @Test
    void 配置里不得残留硬编码的万能验证码() throws IOException {
        // 兜底扫描：任何形如 dev-code=123456（无 env 占位符）的行都是漏洞原型。
        // 单条断言比上面三条更宽——防的是"改了行内容但没改键名"的变体回归。
        List<String> hardcoded = relevantLines().stream()
                .filter(line -> line.startsWith("app.auth.dev-code="))
                .filter(line -> !line.contains("${"))
                .collect(Collectors.toList());

        assertThat(hardcoded)
                .as("dev-code 不得硬编码默认值，必须走 ${APP_AUTH_DEV_CODE:空} 占位")
                .isEmpty();
    }
}
