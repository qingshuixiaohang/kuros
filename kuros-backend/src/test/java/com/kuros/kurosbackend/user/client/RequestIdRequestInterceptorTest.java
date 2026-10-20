package com.kuros.kurosbackend.user.client;

import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdRequestInterceptorTest {

    private final RequestIdRequestInterceptor interceptor = new RequestIdRequestInterceptor();

    @AfterEach
    void clearRequestId() {
        MDC.remove(RequestIdRequestInterceptor.MDC_KEY);
    }

    @Test
    void forwardsCurrentRequestId() {
        String requestId = "4860f41d-86d8-4484-a4ac-422893d69655";
        MDC.put(RequestIdRequestInterceptor.MDC_KEY, requestId);
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertThat(template.headers().get(RequestIdRequestInterceptor.HEADER_NAME)).containsExactly(requestId);
    }

    @Test
    void omitsHeaderWhenNoHttpRequestIdIsPresent() {
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertThat(template.headers()).doesNotContainKey(RequestIdRequestInterceptor.HEADER_NAME);
    }
}
