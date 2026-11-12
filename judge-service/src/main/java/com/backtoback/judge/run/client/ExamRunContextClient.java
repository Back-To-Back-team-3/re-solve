package com.backtoback.judge.run.client;

import java.net.URI;
import java.net.http.HttpClient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.judge.global.error.JudgeErrorCode;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.run.dto.ExamRunRequest;
import com.backtoback.judge.submission.config.SubmissionClientProperties;

/** Contest에 본인 입장·개인 마감·고정 회차의 RUN 권한을 확인한다. (API 명세서 §4.7.1) */
@Component
public class ExamRunContextClient {
    private final RestClient client;

    /** 공통 내부 HTTP의 연결·응답 제한 시간을 사용한다. */
    public ExamRunContextClient(
        RestClient.Builder builder,
        SubmissionClientProperties properties,
        @Value("${judge.clients.contest-base-url:http://contest-service:8080}") URI baseUrl
    ) {
        var http
            = HttpClient
                .newBuilder()
                .connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.timeout());
        client = builder.clone().baseUrl(baseUrl.toString()).requestFactory(factory).build();
    }

    /** 권한과 요청 회차가 모두 확인된 경우에만 실행을 허용한다. */
    public void check(ExamRunRequest request) {
        ContextResult result;
        try {
            result
                = client
                    .post()
                    .uri("/internal/v1/contest/contexts/validate")
                    .body(
                        new ContextRequest(
                            request.context(),
                            request.contextId(),
                            request.memberId(),
                            request.problemId(),
                            request.problemRevisionId(),
                            "RUN"
                        )
                    )
                    .retrieve()
                    .body(ContextResult.class);
        } catch (RestClientResponseException failure) {
            var code = switch (failure.getStatusCode().value()) {
                case 403 -> JudgeErrorCode.CONTEXT_ACCESS_DENIED;
                case 404 -> JudgeErrorCode.PROBLEM_REVISION_NOT_INCLUDED;
                case 409 -> JudgeErrorCode.EXAM_CLOSED;
                default -> CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE;
            };
            throw new JudgeException(code);
        } catch (RestClientException failure) {
            throw new JudgeException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        }
        if (result == null || result.allowed() == null) {
            throw new JudgeException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        }
        if (!result.allowed()) {
            throw new JudgeException(JudgeErrorCode.CONTEXT_ACCESS_DENIED);
        }
        if (!request.problemRevisionId().equals(result.problemRevisionId())) {
            throw new JudgeException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        }
    }

    /** 실행 권한 검증에 필요한 시험·회원·회차 식별자다. */
    private record ContextRequest(
        String context,
        String contextId,
        String memberId,
        String problemId,
        String problemRevisionId,
        String action
    ) {
    }

    /** 권한 판정과 검증된 고정 회차다. */
    private record ContextResult(Boolean allowed, String problemRevisionId) {
    }
}
