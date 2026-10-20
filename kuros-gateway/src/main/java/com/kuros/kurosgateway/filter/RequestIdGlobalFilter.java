package com.kuros.kurosgateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
public class RequestIdGlobalFilter implements GlobalFilter, Ordered {

    static final String HEADER_NAME = "X-Request-Id";

    private static final Logger log = LoggerFactory.getLogger(RequestIdGlobalFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String requestId = UUID.randomUUID().toString();
        ServerWebExchange routed = exchange.mutate()
                .request(request -> request.headers(headers -> headers.set(HEADER_NAME, requestId)))
                .build();
        routed.getResponse().getHeaders().set(HEADER_NAME, requestId);
        routed.getResponse().beforeCommit(() -> {
            routed.getResponse().getHeaders().set(HEADER_NAME, requestId);
            return Mono.empty();
        });

        return chain.filter(routed).doFinally(signal -> {
            HttpStatusCode status = routed.getResponse().getStatusCode();
            log.info("request completed requestId={} method={} path={} status={}",
                    requestId,
                    routed.getRequest().getMethod(),
                    routed.getRequest().getURI().getRawPath(),
                    status == null ? "unknown" : status.value());
        });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
