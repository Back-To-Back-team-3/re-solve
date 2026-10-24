package com.backtoback.judge.global.config;

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

/**
 * 채점 서비스 공통 설정의 추적 필터 등록과 HTTP 요청 추적 ID 전파를 검증한다.
 */
class CommonConfigurationTest {
    private final ApplicationContextRunner runner
        = new ApplicationContextRunner().withUserConfiguration(CommonConfiguration.class);

    /**
     * 안전하지 않은 추적 ID를 교체하고 기존 MDC를 복원하는지 검증한다.
     *
     * @throws Exception 테스트 요청 처리 중 오류가 발생한 경우
     */
    @Test
    @DisplayName("안전하지 않은 요청 추적 ID를 교체하고 기존 MDC를 복원한다")
    void replacesUnsafeIncomingIdAndRestoresPreviousMdc() throws Exception {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(CorrelationIdFilter.class);
            var request = new MockHttpServletRequest("GET", "/api/v1/submissions");
            request.addHeader(CorrelationIdFilter.HEADER, "untrusted\nforged-log");
            var response = new MockHttpServletResponse();
            MDC.put(CorrelationIdFilter.MDC_KEY, "previous");
            try {
                context
                    .getBean(CorrelationIdFilter.class)
                    .doFilter(request, response, (servletRequest, servletResponse) -> {
                        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).matches("[0-9a-f-]{36}");
                    });
                assertThat(response.getHeader(CorrelationIdFilter.HEADER)).matches("[0-9a-f-]{36}");
                assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo("previous");
            } finally {
                MDC.clear();
            }
        });
    }

    /**
     * 현재 추적 ID를 나가는 HTTP 요청에 전달하는지 검증한다.
     */
    @Test
    @DisplayName("현재 요청 추적 ID를 외부 HTTP 요청에 전달한다")
    void propagatesCurrentCorrelationIdToOutgoingHttpRequest() {
        var builder = RestClient.builder();
        runner.run(context -> {
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
            builder.build().get().uri("http://internal.test/probe").retrieve().toBodilessEntity();
            server.verify();
        } finally {
            MDC.clear();
        }
    }
}
