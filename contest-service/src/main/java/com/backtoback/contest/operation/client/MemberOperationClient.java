package com.backtoback.contest.operation.client;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.backtoback.contest.exam.client.MemberClientProperties;

/** 원래 회원 탈퇴 작업의 종결 여부를 조회한다 (API 명세서 §1.7.3). */
@Component
public class MemberOperationClient {
    private final RestClient client;

    /** 내부 조회의 연결과 응답 시간을 제한한다. */
    @Autowired
    public MemberOperationClient(RestClient.Builder builder, MemberClientProperties properties) {
        var factory
            = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(2));
        client = builder.clone().baseUrl(properties.baseUrl()).requestFactory(factory).build();
    }

    MemberOperationClient(RestClient client) {
        this.client = client;
    }

    /** 조회 실패와 알 수 없는 응답은 차단 해제의 근거가 되지 않는다. */
    public Result find(long memberId, String operationId) {
        for (int attempt = 0;; attempt++) {
            try {
                var result
                    = client
                        .get()
                        .uri("/internal/v1/members/{memberId}/operations/{operationId}", memberId, operationId)
                        .retrieve()
                        .body(Result.class);
                if (result == null || !operationId.equals(result.operationId())
                    || !Set.of("PENDING", "COMMITTED", "ABORTED").contains(result.state())
                    || result.sourceVersion() == null || !result.sourceVersion().matches("[0-9]+")
                    || result.resourceStatus() == null || result.resourceStatus().isBlank()) {
                    throw new IllegalStateException("Invalid member operation response");
                }
                Long.parseLong(result.sourceVersion());
                return result;
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

    /** 회원 서비스가 보존한 원래 작업 결과다. */
    public record Result(String operationId, String state, String sourceVersion, String resourceStatus) {
    }
}
