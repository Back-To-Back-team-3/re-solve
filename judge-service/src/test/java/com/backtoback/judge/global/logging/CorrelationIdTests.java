package com.backtoback.judge.global.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class CorrelationIdTests {
    @Test
    void replacesUnsafeIncomingIdAndRestoresPreviousMdc() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/problems");
        request.addHeader(CorrelationIdFilter.HEADER, "untrusted\nforged-log");
        var response = new MockHttpServletResponse();
        MDC.put(CorrelationIdFilter.MDC_KEY, "previous");
        try {
            new CorrelationIdFilter().doFilter(request, response, (servletRequest, servletResponse) -> {
                assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).matches("[0-9a-f-]{36}");
            });
            assertThat(response.getHeader(CorrelationIdFilter.HEADER)).matches("[0-9a-f-]{36}");
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo("previous");
        } finally {
            MDC.clear();
        }
    }

    @Test
    void propagatesCurrentCorrelationIdToOutgoingHttpRequest() {
        var builder = RestClient.builder();
        new HttpClientConfiguration().correlationIdCustomizer().customize(builder);
        var server = MockRestServiceServer.bindTo(builder).build();
        server
            .expect(requestTo("http://internal.test/probe"))
            .andExpect(header(CorrelationIdFilter.HEADER, "request-123"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        MDC.put(CorrelationIdFilter.MDC_KEY, "request-123");
        try {
            builder.build().get().uri("http://internal.test/probe").retrieve().toBodilessEntity();
            server.verify();
        } finally {
            MDC.clear();
        }
    }
}
