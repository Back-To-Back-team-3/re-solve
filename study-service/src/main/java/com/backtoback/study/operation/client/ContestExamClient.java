package com.backtoback.study.operation.client;

import java.net.http.HttpClient;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.operation.config.OperationClientProperties;

/**
 * contest 서비스 내부 시험 목록 API 클라이언트. 장애 시 {@code COMMON_DEPENDENCY_UNAVAILABLE}로 변환한다
 * (API 명세서 §5.3.4).
 */
@Component
public class ContestExamClient {

    private final RestClient contest;

    public ContestExamClient(RestClient.Builder builder, OperationClientProperties properties) {
        var http
            = HttpClient
                .newBuilder()
                .connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.timeout());
        contest = builder.clone().baseUrl(properties.contestBaseUrl().toString()).requestFactory(factory).build();
    }

    /**
     * studyId에 연결된 활성 시험(SCHEDULED·IN_PROGRESS)이 있는지 확인한다. 모든 페이지를 순회하며
     * SCHEDULED·IN_PROGRESS 시험이 하나라도 나오면 즉시 true를 반환한다.
     *
     * @param studyId 확인할 스터디 ID
     * @return 활성 시험이 1건 이상이면 true
     * @throws StudyException {@code COMMON_DEPENDENCY_UNAVAILABLE}: 조회 실패인 경우
     */
    public boolean hasActiveExams(Long studyId) {
        String cursor = null;
        do {
            final String currentCursor = cursor;
            ContestExamResponse response;
            try {
                response = contest.get().uri(uriBuilder -> {
                    var b = uriBuilder.path("/internal/v1/contest/studies/{studyId}/exams").queryParam("size", 20);
                    if (currentCursor != null) {
                        b = b.queryParam("after", currentCursor);
                    }
                    return b.build(studyId);
                }).retrieve().body(ContestExamResponse.class);
            } catch (RestClientException exception) {
                throw new StudyException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
            }
            if (response == null || response.items() == null) {
                throw new StudyException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
            }
            boolean hasActive
                = response
                    .items()
                    .stream()
                    .anyMatch(i -> "SCHEDULED".equals(i.status()) || "IN_PROGRESS".equals(i.status()));
            if (hasActive) {
                return true;
            }
            cursor = response.nextCursor();
        } while (cursor != null);
        return false;
    }
}
