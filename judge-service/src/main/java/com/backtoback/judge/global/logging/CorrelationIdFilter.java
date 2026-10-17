package com.backtoback.judge.global.logging;

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

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Logger LOG = LoggerFactory.getLogger(CorrelationIdFilter.class);

    /**
     * 요청 추적 ID를 MDC와 응답 헤더에 설정하고 처리 후 기존 MDC를 복원한다.
     * 유효하지 않은 요청 추적 ID는 새 UUID로 대체한다.
     *
     * @param request 현재 HTTP 요청
     * @param response 현재 HTTP 응답
     * @param chain 다음 필터와 요청 처리기로 이어지는 체인
     * @throws ServletException 후속 요청 처리에서 서블릿 오류가 발생한 경우
     * @throws IOException 후속 요청 처리 중 입출력 오류가 발생한 경우
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException,
        IOException {
        // 기존에 MDC에 저장된 키가 있는지 확인하는 방어적 코드
        String previous = MDC.get(MDC_KEY);

        // 헤더에 저장된 ID 추출
        String received = request.getHeader(HEADER);

        // received가 있다면 그대로 사용, 없다면 새로 만듦
        String correlationId
            = received != null && VALID_ID.matcher(received).matches() ? received : UUID.randomUUID().toString();

        // 정해진 correlationId를 MDC에 저장
        MDC.put(MDC_KEY, correlationId);
        // 헤더에도 결정된 ID 맞춰줌
        response.setHeader(HEADER, correlationId);

        try {
            chain.doFilter(request, response);
        } finally {
            // URL·쿼리·헤더·본문에는 사용자 정보나 코드가 포함될 수 있다.
            LOG.info("Request completed: method={}, status={}", request.getMethod(), response.getStatus());

            // 서버는 스레드를 재사용하므로, 정리하지 않으면 다음 요청의 로그에 이전 요청 ID가 붙을 수 있기 때문이야.
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        }
    }
}
