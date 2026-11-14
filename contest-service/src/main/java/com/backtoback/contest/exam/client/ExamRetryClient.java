package com.backtoback.contest.exam.client;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Judge의 시험 실패 재처리 계약을 호출한다 (API 명세서 §3.3.7). */
@Component
public class ExamRetryClient {
    private final RestClient client;

    /** 내부 호출의 연결과 응답 시간을 제한한다. */
    @Autowired
    public ExamRetryClient(RestClient.Builder builder, ExamRunProperties properties) {
        var factory
            = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(2));
        this.client = builder.clone().baseUrl(properties.baseUrl()).requestFactory(factory).build();
    }

    ExamRetryClient(RestClient client) {
        this.client = client;
    }

    /** 호출 결과가 불명이면 호출자가 원래 작업을 보존해 같은 요청으로 복구한다. */
    public Result retry(long submissionId, Request request) {
        for (int attempt = 0;; attempt++) {
            try {
                return send(submissionId, request);
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

    private Result send(long submissionId, Request request) {
        var result
            = client
                .post()
                .uri("/internal/v1/judge/submissions/{submissionId}/retry", submissionId)
                .body(request)
                .retrieve()
                .body(Result.class);
        if (result == null || !Long.toString(submissionId).equals(result.submissionId())
            || result.judgeAttempt() == null || (long)result.judgeAttempt() != (long)request.expectedJudgeAttempt() + 1
            || !"QUEUED".equals(result.status())) {
            throw new IllegalStateException("Invalid retry response");
        }
        return result;
    }

    /** 동일 작업 재요청에 사용할 원본 요청이다. */
    public record Request(
        String context,
        String receiptId,
        int expectedJudgeAttempt,
        String operationId,
        String actorMemberId,
        String reason
    ) {
    }

    /** Judge가 확인한 재처리 목표 회차다. */
    public record Result(String submissionId, Integer judgeAttempt, String status) {
    }
}
