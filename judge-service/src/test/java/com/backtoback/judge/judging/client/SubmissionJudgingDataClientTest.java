package com.backtoback.judge.judging.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.submission.config.SubmissionClientProperties;
import com.sun.net.httpserver.HttpServer;

/** 대사용 조회가 일반 클라이언트의 긴 제한시간에 영향받지 않는지 확인한다. */
class SubmissionJudgingDataClientTest {
    /**
     * 응답 헤더와 본문의 지연을 각각 실제 HTTP 연결에서 재현한다.
     *
     * @param bodyDelayed 헤더 대신 응답 본문을 지연할지 여부
     * @throws Exception 로컬 HTTP 서버를 준비하지 못한 경우
     */
    @ParameterizedTest
    @ValueSource(
        booleans = {
            false,
            true
        }
    )
    @DisplayName("일반 타임아웃이 30초여도 대사용 헤더·본문 조회는 짧은 제한시간에 실패한다")
    void boundsReconciliationTimeout(boolean bodyDelayed) throws Exception {
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try {
                    if (bodyDelayed) {
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, 100);
                        exchange.getResponseBody().write('{');
                        exchange.getResponseBody().flush();
                    }
                    release.await();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
            try {
                var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
                var client
                    = new SubmissionJudgingDataClient(
                        RestClient.builder(),
                        new SubmissionClientProperties(uri, uri, Duration.ofSeconds(30))
                    );
                assertTimeoutPreemptively(
                    Duration.ofSeconds(8),
                    () -> assertThatThrownBy(() -> client.findForReconciliation(1, 1))
                        .isInstanceOf(JudgeException.class)
                );
            } finally {
                release.countDown();
                server.stop(0);
            }
        }
    }
}
