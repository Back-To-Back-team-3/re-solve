package com.backtoback.study.hint.client;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.study.global.error.StudyException;

/**
 * problem-service 힌트 문맥 조회 RestClient 구현체 (api-spec §2.6.6).
 * 2초 타임아웃, 100ms·300ms 간격 2회 재시도. 실패 시 fail-closed (503).
 */
@Component
@Profile("!local")
public class ProblemHintContextRestClient implements ProblemHintContextClient {

    private final RestClient client;

    public ProblemHintContextRestClient(RestClient.Builder builder, HintContextClientProperties properties) {
        var factory
            = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(2));
        this.client = builder.clone().baseUrl(properties.problemBaseUrl()).requestFactory(factory).build();
    }

    @Override
    public HintContextResponse getHintContext(Long problemId) {
        return fetch(
            () -> client
                .get()
                .uri("/internal/v1/problems/{problemId}/hint-context", problemId)
                .retrieve()
                .body(HintContextResponse.class)
        );
    }

    private <T> T fetch(Supplier<T> request) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("hint-context lookup must occur outside a transaction");
        }
        for (int attempt = 0;; attempt++) {
            try {
                return request.get();
            } catch (RuntimeException failure) {
                boolean retryable
                    = failure instanceof ResourceAccessException
                        || failure instanceof RestClientResponseException response
                            && (response.getStatusCode().value() == 502 || response.getStatusCode().value() == 503
                                || response.getStatusCode().value() == 504);
                if (!retryable || attempt == 2) {
                    throw new StudyException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE, failure);
                }
                try {
                    Thread.sleep(attempt == 0 ? 100 : 300);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new StudyException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE, interrupted);
                }
            }
        }
    }
}
