package com.backtoback.study.admin.client;

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
 * judge-service AC 이력 조회 클라이언트 (api-spec §3.3.4).
 * 2초 타임아웃, 100ms·300ms 간격 2회 재시도. 실패 시 fail-closed (503).
 */
@Component
@Profile("!local")
public class JudgeAcHistoryClient {

    private final RestClient client;

    public JudgeAcHistoryClient(RestClient.Builder builder, JudgeClientProperties properties) {
        var factory
            = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(2));
        this.client = builder.clone().baseUrl(properties.baseUrl()).requestFactory(factory).build();
    }

    /** 테스트에서 미리 구성된 RestClient를 직접 주입할 때 사용한다. */
    JudgeAcHistoryClient(RestClient restClient) {
        this.client = restClient;
    }

    /**
     * 회원별 PRACTICE AC 이력 한 페이지를 조회한다.
     *
     * @param userId 재동기화할 회원 ID
     * @param after 마지막 제출 ID; 첫 페이지이면 null
     * @param size 최대 응답 행 수 (1–500)
     * @return 정답 이력 한 페이지와 다음 커서
     */
    public AcceptedSubmissionPageResponse fetchPage(long userId, String after, int size) {
        return fetch(() -> client.get().uri(uriBuilder -> {
            uriBuilder
                .path("/internal/v1/submissions/accepted")
                .queryParam("context", "PRACTICE")
                .queryParam("userId", userId)
                .queryParam("size", size);
            if (after != null) {
                uriBuilder.queryParam("after", after);
            }
            return uriBuilder.build();
        }).retrieve().body(AcceptedSubmissionPageResponse.class));
    }

    private <T> T fetch(Supplier<T> request) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("judge AC history lookup must occur outside a transaction");
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
