package com.kuros.kurosbackend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("production")
public class ProductionInternalTokenValidator {

    private static final String TEMPLATE_PLACEHOLDER = "REPLACE_WITH_LONG_RANDOM_INTERNAL_TOKEN";

    public ProductionInternalTokenValidator(@Value("${app.internal.token:}") String token) {
        if (token == null || token.isBlank() || TEMPLATE_PLACEHOLDER.equals(token.trim())) {
            throw new IllegalStateException(
                    "production requires a non-placeholder APP_INTERNAL_TOKEN shared by backend and user");
        }
    }
}
