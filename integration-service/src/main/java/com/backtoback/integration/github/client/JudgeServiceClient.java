package com.backtoback.integration.github.client;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.integration.github.client.dto.AcceptedSubmissionCodeResponse;
import com.backtoback.integration.github.config.JudgeProperties;
import com.backtoback.integration.global.error.IntegrationException;

import lombok.extern.slf4j.Slf4j;

/**
 * judge-service 내부 API 클라이언트 (api-spec.md &gt; 3.3.3, policy-and-state.md &gt; 9.3 코드 조회).
 * 응답 본문의 제출 코드는 로그에 남기지 않는다 (policy-and-state.md &gt; 9.4 로깅).
 */
@Slf4j
@Component
public class JudgeServiceClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    private final JudgeProperties properties;
    private final RestClient restClient;

    @Autowired
    public JudgeServiceClient(JudgeProperties properties) {
        this(properties, RestClient.builder().requestFactory(timeoutRequestFactory()).build());
    }

    JudgeServiceClient(JudgeProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    private static SimpleClientHttpRequestFactory timeoutRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    /**
     * PRACTICE AC 제출의 정답 코드를 조회한다 (GET /internal/v1/submissions/{submissionId}/accepted/code).
     * 조회에 실패하면 워커가 {@code RETRY_WAITING}으로 전이하도록 의존 서비스 장애 예외로 변환한다 (scheduler.md &gt; 8.1).
     *
     * @param submissionId 제출 ID
     * @param memberId 연동 회원 ID (제출 소유자와 일치해야 함)
     * @return 정답 코드 응답
     */
    public AcceptedSubmissionCodeResponse getAcceptedCode(Long submissionId, Long memberId) {
        try {
            AcceptedSubmissionCodeResponse response
                = restClient
                    .get()
                    .uri(
                        properties.getBaseUrl()
                            + "/internal/v1/submissions/{submissionId}/accepted/code?memberId={memberId}",
                        submissionId,
                        memberId
                    )
                    .retrieve()
                    .body(AcceptedSubmissionCodeResponse.class);
            if (response == null || response.sourceCode() == null || response.language() == null) {
                throw new IntegrationException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
            }
            return response;
        } catch (RestClientException e) {
            log.warn("judge-service 정답 코드 조회 실패: submissionId={}, error={}", submissionId, e.getMessage());
            throw new IntegrationException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE, e);
        }
    }
}
