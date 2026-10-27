package com.backtoback.problem.global.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.backtoback.common.web.logging.CorrelationIdFilter;
import com.backtoback.problem.global.config.CommonConfiguration;

/** 요청 추적 ID의 안전한 처리와 나가는 HTTP 요청으로의 전달을 확인한다. */
class CorrelationIdTest {
    @Test
    @DisplayName("안전하지 않은 추적 ID를 교체하고 이전 MDC 값을 복원한다")
    void replacesUnsafeIncomingIdAndRestoresPreviousMdc() throws Exception {
        // given
        var request = new MockHttpServletRequest("GET", "/api/v1/problems");
        request.addHeader(CorrelationIdFilter.HEADER, "untrusted\nforged-log");
        var response = new MockHttpServletResponse();
        MDC.put(CorrelationIdFilter.MDC_KEY, "previous");
        try {
            // when
            new CorrelationIdFilter().doFilter(request, response, (servletRequest, servletResponse) -> {
                assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).matches("[0-9a-f-]{36}");
            });
            // then
            assertThat(response.getHeader(CorrelationIdFilter.HEADER)).matches("[0-9a-f-]{36}");
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo("previous");
        } finally {
            MDC.clear();
        }
    }

    @Test
    @DisplayName("현재 추적 ID를 나가는 HTTP 요청에 전달한다")
    void propagatesCurrentCorrelationIdToOutgoingHttpRequest() {
        // given
        var builder = RestClient.builder();
        new ApplicationContextRunner().withUserConfiguration(CommonConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            context.getBean(RestClientCustomizer.class).customize(builder);
        });
        var server = MockRestServiceServer.bindTo(builder).build();
        server
            .expect(requestTo("http://internal.test/probe"))
            .andExpect(header(CorrelationIdFilter.HEADER, "request-123"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        MDC.put(CorrelationIdFilter.MDC_KEY, "request-123");
        try {
            // when
            builder.build().get().uri("http://internal.test/probe").retrieve().toBodilessEntity();
            // then
            server.verify();
        } finally {
            MDC.clear();
        }
    }
}
