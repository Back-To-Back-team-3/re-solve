package com.backtoback.study.assignment.client;

import java.net.http.HttpClient;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.study.assignment.config.AssignmentClientProperties;
import com.backtoback.study.global.error.StudyException;

/**
 * problem 서비스 내부 summaries API 클라이언트. 장애 시 {@code COMMON_DEPENDENCY_UNAVAILABLE}로 변환한다
 * (API 명세서 §5.4.1).
 */
@Component
public class ProblemSummariesClient {

    private final RestClient problem;

    public ProblemSummariesClient(RestClient.Builder builder, AssignmentClientProperties properties) {
        var http
            = HttpClient
                .newBuilder()
                .connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.timeout());
        problem = builder.clone().baseUrl(properties.problemBaseUrl().toString()).requestFactory(factory).build();
    }

    /**
     * 문제 목록의 공개 가용 여부와 현재 회차 정보를 조회한다.
     *
     * @param problemIds 조회할 문제 ID 목록
     * @throws StudyException {@code COMMON_DEPENDENCY_UNAVAILABLE}: 조회 실패인 경우
     */
    public ProblemSummariesResponse summaries(List<Long> problemIds) {
        String ids = problemIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        try {
            ProblemSummariesResponse response
                = problem
                    .get()
                    .uri(
                        uriBuilder -> uriBuilder
                            .path("/internal/v1/problems/summaries")
                            .queryParam("ids", ids)
                            .queryParam("purpose", "ASSIGNMENT")
                            .build()
                    )
                    .retrieve()
                    .body(ProblemSummariesResponse.class);
            if (response == null) {
                throw new StudyException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
            }
            return response;
        } catch (RestClientException exception) {
            throw new StudyException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        }
    }
}
