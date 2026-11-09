package com.backtoback.contest.exam.client;

import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

/** Judge 내부 Run에 고정 회차를 전달하며 전체 호출 시간을 30초로 제한한다. */
@Component
public class ExamRunClient {
    private final ClientFactory clients;

    /** Judge 주소와 요청 생성기를 보관한다. */
    @Autowired
    public ExamRunClient(RestClient.Builder builder, ExamRunProperties properties) {
        this.clients = remaining -> {
            var factory
                = new JdkClientHttpRequestFactory(
                    HttpClient
                        .newBuilder()
                        .connectTimeout(Duration.ofNanos(Math.min(remaining, TimeUnit.SECONDS.toNanos(2))))
                        .build()
                );
            factory.setReadTimeout(Duration.ofNanos(remaining));
            return builder.clone().baseUrl(properties.baseUrl()).requestFactory(factory).build();
        };
    }

    /** 모의 HTTP 클라이언트 생성기를 사용한다. */
    ExamRunClient(ClientFactory clients) {
        this.clients = clients;
    }

    /** 실행 중 충돌과 Run 시간 초과는 그대로 전달하고 시간 초과를 재시도하지 않는다. */
    public Result run(Request request) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        for (int attempt = 0;; attempt++) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new ContestException(ContestErrorCode.RUN_TIMEOUT);
            }
            var client = clients.create(remaining);
            try {
                var response = client.post().uri("/internal/v1/judge/runs").body(request).retrieve().body(Result.class);
                if (response == null || response.results() == null || response.completedAt() == null) {
                    throw new IllegalStateException("Invalid run response");
                }
                return response;
            } catch (RestClientResponseException failure) {
                String body = failure.getResponseBodyAsString();
                int status = failure.getStatusCode().value();
                if (status == 504 && body.contains("RUN_TIMEOUT")) {
                    throw new ContestException(ContestErrorCode.RUN_TIMEOUT, failure);
                }
                if (status == 429 && body.contains("RUN_IN_PROGRESS")) {
                    throw new ContestException(ContestErrorCode.RUN_IN_PROGRESS, failure);
                }
                if (status == 400 && body.contains("UNSUPPORTED_LANGUAGE")) {
                    throw new ContestException(ContestErrorCode.UNSUPPORTED_LANGUAGE, failure);
                }
                if (status == 413 && body.contains("CODE_TOO_LARGE")) {
                    throw new ContestException(ContestErrorCode.CODE_TOO_LARGE, failure);
                }
                if ((status != 502 && status != 503 && status != 504) || attempt == 2) {
                    throw new ContestException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE, failure);
                }
            } catch (ResourceAccessException failure) {
                if (isTimeout(failure) || System.nanoTime() >= deadline) {
                    throw new ContestException(ContestErrorCode.RUN_TIMEOUT, failure);
                }
                if (attempt == 2) {
                    throw new ContestException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE, failure);
                }
            } catch (RuntimeException failure) {
                throw new ContestException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE, failure);
            }
            long pause = TimeUnit.MILLISECONDS.toNanos(attempt == 0 ? 100 : 300);
            if (deadline - System.nanoTime() <= pause) {
                throw new ContestException(ContestErrorCode.RUN_TIMEOUT);
            }
            try {
                TimeUnit.NANOSECONDS.sleep(pause);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new ContestException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE, interrupted);
            }
        }
    }

    /** 예외 원인에 HTTP 응답 시간 초과가 포함됐는지 확인한다. */
    private boolean isTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpConnectTimeoutException) {
                return false;
            }
            if (cause instanceof HttpTimeoutException || cause instanceof java.net.SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** 남은 실행 시간에 맞는 HTTP 클라이언트를 만든다. */
    interface ClientFactory {
        /** 남은 나노초를 연결·응답 시간 제한에 반영한다. */
        RestClient create(long remaining);
    }

    /** Judge 내부 실행 입력이다. */
    public record Request(
        String context,
        String contextId,
        String memberId,
        String problemId,
        String problemRevisionId,
        String language,
        String sourceCode,
        List<CustomTest> customTests
    ) {
    }
    /** 사용자가 전달한 인수 배열이다. */
    public record CustomTest(List<Object> args) {
    }
    /** 공개 예제와 사용자 입력의 실행 결과다. */
    public record Result(List<Item> results, String completedAt) {
    }
    /** 한 입력의 출력과 실행 측정값이다. */
    public record Item(String type, int index, String output, String error, int timeMs, int memoryKb) {
    }
}
