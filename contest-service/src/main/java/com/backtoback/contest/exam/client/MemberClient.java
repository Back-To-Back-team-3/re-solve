package com.backtoback.contest.exam.client;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.contest.global.error.ContestException;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * 회원 내부 API에서 최신 상태와 표시 정보 스냅샷을 조회한다 (API 명세서 1.7.1).
 */
@Component
public class MemberClient {
    private final RestClient client;
    private final CircuitBreaker circuitBreaker;

    @Autowired
    public MemberClient(RestClient.Builder builder, MemberClientProperties properties) {
        var factory
            = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(2));
        this.client = builder.baseUrl(properties.baseUrl()).requestFactory(factory).build();
        this.circuitBreaker = CircuitBreaker.ofDefaults("member-status");
    }

    MemberClient(RestClient client) {
        this.client = client;
        this.circuitBreaker = CircuitBreaker.ofDefaults("member-status-test");
    }

    /**
     * 응답 오류·통신 장애·불완전한 응답은 의존 서비스 오류로 처리한다.
     */
    public MemberStatusResponse fetch(long memberId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Member lookup must occur outside a transaction");
        }
        try {
            return circuitBreaker.executeSupplier(() -> {
                var response
                    = client
                        .get()
                        .uri("/internal/v1/members/{memberId}/status", memberId)
                        .retrieve()
                        .body(MemberStatusResponse.class);
                if (response == null) {
                    throw new IllegalArgumentException("Empty member response");
                }
                response.validate(memberId);
                return response;
            });
        } catch (RuntimeException failure) {
            throw new ContestException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        }
    }
}
