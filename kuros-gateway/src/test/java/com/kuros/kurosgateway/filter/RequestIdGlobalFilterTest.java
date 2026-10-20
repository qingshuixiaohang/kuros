package com.kuros.kurosgateway.filter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdGlobalFilterTest {

    @Test
    void replacesClientSuppliedIdAndUsesSameIdForDownstreamAndResponse() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/posts")
                .header("X-Request-Id", "attacker-controlled-id")
                .build());
        String[] downstreamId = new String[1];
        GatewayFilterChain chain = forwarded -> {
            downstreamId[0] = forwarded.getRequest().getHeaders().getFirst("X-Request-Id");
            return Mono.empty();
        };

        new RequestIdGlobalFilter().filter(exchange, chain).block();

        assertThat(downstreamId[0]).isNotEqualTo("attacker-controlled-id").matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Request-Id")).isEqualTo(downstreamId[0]);
    }

    @Test
    void completionLogContainsOnlySafeRequestFields() {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestIdGlobalFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest
                    .get("/api/v1/posts?access_token=client-secret")
                    .header("X-Client-Secret", "client-secret")
                    .build());
            GatewayFilterChain chain = forwarded -> {
                forwarded.getResponse().setStatusCode(HttpStatus.NO_CONTENT);
                return Mono.empty();
            };

            new RequestIdGlobalFilter().filter(exchange, chain).block();

            String requestId = exchange.getResponse().getHeaders().getFirst("X-Request-Id");
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage())
                        .contains("requestId=" + requestId)
                        .contains("method=GET")
                        .contains("path=/api/v1/posts")
                        .contains("status=204")
                        .doesNotContain("access_token")
                        .doesNotContain("client-secret")
                        .doesNotContain("signal=");
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
