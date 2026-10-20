package com.kuros.kurosbackend.user.client;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;

public class RequestIdRequestInterceptor implements RequestInterceptor {

    static final String HEADER_NAME = "X-Request-Id";
    static final String MDC_KEY = "requestId";

    @Override
    public void apply(RequestTemplate template) {
        String requestId = MDC.get(MDC_KEY);
        if (requestId != null && !requestId.isBlank()) {
            template.header(HEADER_NAME, requestId);
        }
    }
}
