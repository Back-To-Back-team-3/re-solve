package com.backtoback.common.web.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 요청 추적 ID의 수신·응답·MDC 정리와 나가는 HTTP 요청의 연결을 검증한다.
 * Servlet 필터 체인과 HTTP 응답을 테스트 객체로 구성한다.
 * <p>기준 문서</p>
 * <ul>
 * <li>API 명세서 v1.1 / §0.1 기본 규칙: X-Correlation-Id의 HTTP 구간 전파</li>
 * <li>정책 및 상태 v1.0 / §9.4 운영·보안 정책: 요청 추적 ID의 로그 연결</li>
 * </ul>
 */
class CorrelationIdTest {
    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    /**
     * 테스트에서 설정한 추적 ID가 다음 테스트에 남지 않도록 정리한다.
     */
    @AfterEach
    void tearDown() {
        MDC.remove(CorrelationIdFilter.MDC_KEY);
    }

    /**
     * 요청 처리 중 MDC와 응답이 같은 ID를 사용하고 정상 종료 후 현재 ID가 제거되는지 확인한다.
     *
     * @throws Exception 필터 체인의 요청 처리에 실패한 경우
     */
    @Test
    @DisplayName("유효한 추적 ID를 MDC와 응답에 유지하고 정상 종료 후 제거한다")
    void preservesIncomingIdAndClearsMdc() throws Exception {
        // given
        var request = request("request-123");
        var response = new MockHttpServletResponse();

        // when
        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo("request-123");
        });

        // then
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("request-123");
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    /**
     * 헤더 누락과 줄바꿈이 포함된 ID를 UUID로 대체하고 처리 중 MDC에도 같은 값을 사용하는지 확인한다.
     *
     * @param received 요청 추적 ID; null이면 헤더 생략
     * @throws Exception 필터 체인의 요청 처리 또는 UUID 변환에 실패한 경우
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "unsafe\nforged-id")
    @DisplayName("추적 ID가 없거나 안전하지 않으면 UUID로 대체한다")
    void replacesMissingOrUnsafeId(String received) throws Exception {
        // given
        var request = request(received);
        var response = new MockHttpServletResponse();

        // when
        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            String generated = response.getHeader(CorrelationIdFilter.HEADER);
            assertThat(UUID.fromString(generated).toString()).isEqualTo(generated);
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo(generated);
        });

        // then
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isNotEqualTo(received);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    /**
     * 요청 처리 예외를 그대로 전달하면서 기존 MDC 값을 복원하는지 확인한다.
     */
    @Test
    @DisplayName("요청 처리 중 예외가 발생해도 이전 MDC 추적 ID를 복원한다")
    void restoresPreviousMdcAfterFailure() {
        // given
        var request = request("current-request");
        var response = new MockHttpServletResponse();
        var failure = new IOException("request-processing-failure");
        MDC.put(CorrelationIdFilter.MDC_KEY, "previous-request");

        // when & then
        assertThatThrownBy(() -> filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo("current-request");
            throw failure;
        })).isSameAs(failure);
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("current-request");
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo("previous-request");
    }

    /**
     * 같은 HTTP 클라이언트가 호출 시점의 MDC를 전달하고 MDC가 없으면 호출자 헤더를 유지하는지 확인한다.
     * 클라이언트 생성 시점의 ID를 고정하거나 앞선 호출의 ID를 재사용하는 오류를 구분한다.
     */
    @Test
    @DisplayName("HTTP 호출마다 현재 MDC 추적 ID를 전달하고 MDC가 없으면 기존 헤더를 유지한다")
    void propagatesCorrelationIdAtEachHttpCall() {
        // given
        var builder = RestClient.builder().defaultHeader(CorrelationIdFilter.HEADER, "caller-id");
        new HttpClientConfiguration().correlationIdCustomizer().customize(builder);
        var server = MockRestServiceServer.bindTo(builder).build();
        expectOutgoingCorrelationId(server, "first-request");
        expectOutgoingCorrelationId(server, "second-request");
        expectOutgoingCorrelationId(server, "caller-id");
        var client = builder.build();

        // when
        MDC.put(CorrelationIdFilter.MDC_KEY, "first-request");
        client.get().uri("http://internal.test/probe").retrieve().toBodilessEntity();
        MDC.put(CorrelationIdFilter.MDC_KEY, "second-request");
        client.get().uri("http://internal.test/probe").retrieve().toBodilessEntity();
        MDC.remove(CorrelationIdFilter.MDC_KEY);
        client.get().uri("http://internal.test/probe").retrieve().toBodilessEntity();

        // then
        server.verify();
    }

    /**
     * 추적 ID의 유무에 따라 GET 요청 헤더를 구성한다.
     *
     * @param correlationId 요청 헤더 값; null이면 생략
     * @return 추적 필터에 전달할 테스트 요청
     */
    private MockHttpServletRequest request(String correlationId) {
        var request = new MockHttpServletRequest("GET", "/api/v1/probe");
        if (correlationId != null) {
            request.addHeader(CorrelationIdFilter.HEADER, correlationId);
        }
        return request;
    }

    /**
     * 다음 HTTP 요청이 지정된 추적 ID를 전달하도록 검증 조건과 빈 성공 응답을 등록한다.
     *
     * @param server 외부 HTTP 호출을 대체할 테스트 서버
     * @param correlationId 다음 호출에서 기대하는 추적 ID
     */
    private void expectOutgoingCorrelationId(MockRestServiceServer server, String correlationId) {
        server.expect(request -> {
            assertThat(request.getHeaders().getFirst(CorrelationIdFilter.HEADER)).isEqualTo(correlationId);
        }).andRespond(withSuccess());
    }
}
