package com.backtoback.problem.global.logging;

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
    /**
     * 안전하지 않은 추적 ID를 교체하고 기존 MDC를 복원하는지 검증한다.
     *
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
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

    /**
     * 현재 추적 ID를 나가는 HTTP 요청에 전달하는지 검증한다.
     */
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
