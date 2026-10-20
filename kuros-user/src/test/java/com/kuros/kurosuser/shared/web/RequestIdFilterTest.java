package com.kuros.kurosuser.shared.web;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearRequestId() {
        MDC.remove(RequestIdFilter.MDC_KEY);
    }

    @Test
    void acceptsCanonicalGatewayIdAndBindsItOnlyDuringTheRequest() throws ServletException, IOException {
        String requestId = "4860f41d-86d8-4484-a4ac-422893d69655";
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(RequestIdFilter.HEADER_NAME, requestId);

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isEqualTo(requestId));

        assertThat(response.getHeader(RequestIdFilter.HEADER_NAME)).isEqualTo(requestId);
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void replacesMissingOrInvalidIdsAndClearsMdcWhenTheRequestThrows() {
        for (String invalid : new String[]{null, "not-a-uuid", "4860f41d-86d8-4484-a4ac-422893d69655\nforged"}) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            if (invalid != null) {
                request.addHeader(RequestIdFilter.HEADER_NAME, invalid);
            }

            assertThatThrownBy(() -> filter.doFilter(request, response, (servletRequest, servletResponse) -> {
                String generated = MDC.get(RequestIdFilter.MDC_KEY);
                assertThat(UUID.fromString(generated).toString()).isEqualTo(generated);
                throw new IOException("expected test failure");
            })).isInstanceOf(IOException.class);

            assertThat(response.getHeader(RequestIdFilter.HEADER_NAME)).isNotBlank();
            assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
        }
    }
}
