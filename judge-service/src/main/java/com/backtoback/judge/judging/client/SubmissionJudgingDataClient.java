package com.backtoback.judge.judging.client;

import java.net.http.HttpClient;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.dto.SubmissionJudgingDataResponse;
import com.backtoback.judge.submission.config.SubmissionClientProperties;

/** 고정 채점 자료를 조회하며 외부 응답 원문을 오류에 노출하지 않는다. (API 명세서 §2.6.2) */
@Component
public class SubmissionJudgingDataClient {
    private final RestClient client;

    /**
     * 기존 내부 주소·타임아웃·상관관계 전파 설정을 재사용한다.
     *
     * @param builder 공통 추적 설정을 적용한 빌더
     * @param properties 기존 내부 HTTP 설정
     */
    public SubmissionJudgingDataClient(RestClient.Builder builder, SubmissionClientProperties properties) {
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

    /** 고정 회차와 결과 이벤트 필수값을 검증하며 원문을 오류에 노출하지 않는다. */
    public SubmissionJudgingDataResponse find(long problemId, long revisionId) {
        try {
            var response
                = client
                    .get()
                    .uri("/internal/v1/problems/{problem}/revisions/{revision}/judging/data", problemId, revisionId)
                    .retrieve()
                    .body(SubmissionJudgingDataResponse.class);
            if (response == null) {
                throw new IllegalArgumentException("채점 자료가 없습니다.");
            }
            response.validate(problemId, revisionId);
            return response;
        } catch (RestClientException | IllegalArgumentException failure) {
            throw new JudgeException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        }
    }
}
