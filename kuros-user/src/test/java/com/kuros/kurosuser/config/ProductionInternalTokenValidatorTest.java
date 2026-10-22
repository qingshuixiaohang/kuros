package com.kuros.kurosuser.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionInternalTokenValidatorTest {

    @Test
    void rejectsBlankProductionToken() {
        assertThatThrownBy(() -> new ProductionInternalTokenValidator(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_INTERNAL_TOKEN");
    }

    @Test
    void rejectsTemplatePlaceholder() {
        assertThatThrownBy(() -> new ProductionInternalTokenValidator(
                "REPLACE_WITH_LONG_RANDOM_INTERNAL_TOKEN"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_INTERNAL_TOKEN");
    }

    @Test
    void acceptsExplicitProductionToken() {
        assertThatCode(() -> new ProductionInternalTokenValidator("production-internal-token"))
                .doesNotThrowAnyException();
    }
}
