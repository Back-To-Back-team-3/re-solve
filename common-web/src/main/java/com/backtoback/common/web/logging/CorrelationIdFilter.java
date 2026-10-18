package com.backtoback.common.web.logging;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Servlet 요청의 추적 ID를 MDC와 응답 헤더에 연결하고 요청 종료 후 이전 MDC 값을 복원한다.
 * 영문·숫자·점·밑줄·콜론·하이픈으로 구성된 1~128자 ID를 허용하며, 없거나 잘못된 값은 UUID로 대체한다.
 * 사용하는 서비스에서 컴포넌트 스캔 또는 명시적 import로 등록해야 한다.
 * 로그 패턴에 %X{correlationId}를 사용하면 요청 처리 중의 로그에 같은 ID를 표시할 수 있다.
 * MDC는 현재 처리 스레드에 적용하며 다른 스레드로 자동 전파하지 않는다.
 * <p>기준 문서</p>
 * <ul>
 * <li>API 명세서 v1.1 / §0.1 기본 규칙: X-Correlation-Id 수신과 HTTP 구간 전파</li>
 * <li>정책 및 상태 v1.0 / §9.4 운영·보안 정책: 추적 ID의 로그 연결과 요청 본문·토큰 미기록</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {
    // 요청과 응답에서 사용하는 추적 헤더 이름
    public static final String HEADER = "X-Correlation-Id";

    // 현재 스레드의 로그와 나가는 HTTP 요청이 조회할 MDC 키
    public static final String MDC_KEY = "correlationId";

    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Logger LOG = LoggerFactory.getLogger(CorrelationIdFilter.class);

    /**
     * 검증한 추적 ID로 요청을 처리하고 예외 발생 여부와 관계없이 이전 MDC 상태로 정리한다.
     * 요청 종료 로그에는 HTTP 메서드와 현재 응답 상태만 기록한다.
     *
     * @param request 추적 ID 헤더를 조회할 HTTP 요청
     * @param response 같은 추적 ID를 전달할 HTTP 응답
     * @param chain 다음 필터와 요청 처리기로 이어지는 체인
     * @throws ServletException 후속 요청 처리에서 Servlet 오류가 발생한 경우
     * @throws IOException 후속 요청 처리 중 입출력 오류가 발생한 경우
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException,
        IOException {
        String previous = MDC.get(MDC_KEY);
        String received = request.getHeader(HEADER);
        String correlationId
            = received != null && VALID_ID.matcher(received).matches() ? received : UUID.randomUUID().toString();
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);

        try {
            chain.doFilter(request, response);
        } finally {
            try {
                LOG.info("요청 처리 종료: method={}, status={}", request.getMethod(), response.getStatus());
            } finally {
                // 재사용되는 스레드에 이번 요청의 ID가 남지 않도록 이전 상태를 복원한다.
                if (previous == null) {
                    MDC.remove(MDC_KEY);
                } else {
                    MDC.put(MDC_KEY, previous);
                }
            }
        }
    }
}
