package com.kuros.kurosuser.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    static final String HEADER_NAME = "X-Request-Id";
    static final String MDC_KEY = "requestId";

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = validRequestId(request.getHeader(HEADER_NAME));
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
        }
        response.setHeader(HEADER_NAME, requestId);

        String previousRequestId = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (previousRequestId == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previousRequestId);
            }
        }
    }

    private String validRequestId(String candidate) {
        if (candidate == null || !UUID_PATTERN.matcher(candidate).matches()) {
            return null;
        }
        return UUID.fromString(candidate).toString();
    }
}
