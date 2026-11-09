package com.backtoback.contest.exam.client;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.OffsetDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.contest.global.error.ContestException;

/** Judge에서 원래 시험 접수에 연결된 채점 상태를 조회한다. */
@Component
public class ExamReceiptClient {
    private final RestClient client;

    /** 잠금 밖의 내부 호출에 연결·응답 시간 제한을 적용한다. */
    public ExamReceiptClient(
        RestClient.Builder builder,
        @Value("${JUDGE_SERVICE_URL:http://judge-service:8080}") String baseUrl
    ) {
        var factory
            = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(2));
        client = builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** 원격 장애와 형식 누락은 실패로 남겨 다음 대사에서 재시도한다. */
    public Receipt find(long receiptId) {
        try {
            var result = fetch(receiptId);
            if (result == null || result.found() == null) {
                throw new IllegalStateException("Invalid receipt response");
            }
            return result;
        } catch (RuntimeException failure) {
            throw new ContestException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE, failure);
        }
    }

    /** 연결 실패와 일시적 게이트웨이 장애만 두 차례 재시도한다. */
    private Receipt fetch(long receiptId) {
        for (int attempt = 0;; attempt++) {
            try {
                return client
                    .get()
                    .uri(
                        uri -> uri
                            .path("/internal/v1/judge/submissions/by-receipt")
                            .queryParam("context", "EXAM")
                            .queryParam("receiptId", receiptId)
                            .build()
                    )
                    .retrieve()
                    .body(Receipt.class);
            } catch (RuntimeException failure) {
                boolean retryable
                    = failure instanceof ResourceAccessException
                        || failure instanceof RestClientResponseException response
                            && (response.getStatusCode().value() == 502 || response.getStatusCode().value() == 503
                                || response.getStatusCode().value() == 504);
                if (!retryable || attempt == 2) {
                    throw failure;
                }
                try {
                    Thread.sleep(attempt == 0 ? 100 : 300);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
        }
    }

    /** by-receipt 응답에 포함되는 연결과 종결 결과다. */
    public record Receipt(
        Boolean found,
        String submissionId,
        String context,
        String contextId,
        String receiptId,
        String userId,
        String problemId,
        String problemRevisionId,
        OffsetDateTime submittedAt,
        String status,
        Integer judgeAttempt,
        String verdict,
        Integer passedCount,
        Integer totalCount
    ) {
    }
}
