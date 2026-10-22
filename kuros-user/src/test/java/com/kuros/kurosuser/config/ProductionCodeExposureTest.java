package com.kuros.kurosuser.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionCodeExposureTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DevCodeSafetyValidator.class);

    @Test
    void productionCannotEnableCodeExposureEvenWithoutFixedCode() {
        runner.withPropertyValues("spring.profiles.active=production", "app.auth.dev-code-exposed=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessage(
                            "验证码回显只允许在 dev/test profile 中启用");
                });
    }

    @Test
    void productionCannotBeMadeTrustedByAddingDevProfile() {
        runner.withPropertyValues("spring.profiles.active=production,dev", "app.auth.dev-code=123456")
                .run(context -> assertThat(context).hasFailed());
    }
}
