package com.kuros.kurosuser.config;

import com.kuros.kurosuser.auth.SmsSender;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionSmsSenderValidatorTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ProductionSmsSenderValidator.class)
            .withPropertyValues("spring.profiles.active=production");

    @Test
    void productionRequiresAnSmsSenderAdapter() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasMessage("production requires an SmsSender adapter");
        });
    }

    @Test
    void productionCannotIncludeDevelopmentProfiles() {
        runner.withPropertyValues("spring.profiles.active=production,dev")
                .withBean(SmsSender.class, () -> (phone, code) -> { })
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessage("production cannot be combined with dev or test profiles");
                });
    }

    @Test
    void productionStartsWithAnExplicitAdapter() {
        runner.withBean(SmsSender.class, () -> (phone, code) -> { })
                .run(context -> assertThat(context).hasNotFailed());
    }
}
