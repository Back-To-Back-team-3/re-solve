package com.backtoback.judge.judging.client;

import java.net.http.HttpClient;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.dto.ProblemValidationDataResponse;
import com.backtoback.judge.judging.dto.ProblemValidationRequest;
import com.backtoback.judge.submission.config.SubmissionClientProperties;

/** 고정 검증 자료를 조회하며 외부 응답 원문을 오류에 노출하지 않는다. (API 명세서 §2.6.8) */
@Component
public class ProblemValidationDataClient {
    private final RestClient client;

    /**
     * 기존 내부 주소·타임아웃·상관관계 전파 설정을 재사용한다.
     *
     * @param builder 공통 추적 설정을 적용한 빌더
     * @param properties 기존 내부 HTTP 설정
     */
    public ProblemValidationDataClient(RestClient.Builder builder, SubmissionClientProperties properties) {
        var http
            = HttpClient
                .newBuilder()
                .connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.timeout());
        client = builder.clone().baseUrl(properties.problemBaseUrl().toString()).requestFactory(factory).build();
    }

    /**
     * 요청과 정확히 연결된 고정 원문을 트랜잭션 밖에서 조회한다.
     * 자료 미존재도 사용자 코드 오답이 아닌 의존성 실패로 전달한다.
     *
     * @param request 저장된 불변 요청
     * @return 검증한 실행 자료
     * @throws JudgeException {@code COMMON_DEPENDENCY_UNAVAILABLE}: 조회 실패 또는 응답 계약 불일치인 경우
     */
    public ProblemValidationDataResponse find(ProblemValidationRequest request) {
        try {
            var response
                = client
                    .get()
                    .uri("/internal/v1/problems/validations/{id}/data", request.validationId())
                    .retrieve()
                    .body(ProblemValidationDataResponse.class);
            if (response == null) {
                throw new IllegalArgumentException("검증 자료가 없습니다.");
            }
            response.validate(request);
            return response;
        } catch (RestClientException | IllegalArgumentException failure) {
            throw new JudgeException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        }
    }
}
