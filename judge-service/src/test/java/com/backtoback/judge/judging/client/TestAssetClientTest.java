package com.backtoback.judge.judging.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.config.TestDataProperties;
import com.backtoback.judge.judging.dto.SubmissionJudgingDataResponse.Asset;
import com.sun.net.httpserver.HttpServer;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class TestAssetClientTest {
    @Mock
    private S3Client s3;

    @Test
    @DisplayName("고정 객체 버전과 체크섬을 확인하고 JSON 문자열의 정밀도를 보존한다")
    void readsFixedVersion() throws Exception {
        byte[] bytes
            = "{\"argumentTexts\":{\"value\":\"9223372036854775807\"},\"expectedReturnText\":\"1\"}"
                .getBytes(StandardCharsets.UTF_8);
        given(s3.getObject(any(GetObjectRequest.class))).willReturn(stream(bytes));
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var client = new TestAssetClient(s3, new TestDataProperties("bucket"), JsonMapper.builder().build());
        assertThat(client.read(new Asset("key", "v1", hash)).argumentTexts().get("value"))
            .isEqualTo("9223372036854775807");
        var request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3).getObject(request.capture());
        assertThat(request.getValue().versionId()).isEqualTo("v1");
    }

    @Test
    @DisplayName("체크섬이 다른 원문은 실행 자료로 사용하지 않는다")
    void rejectsChangedContent() {
        given(s3.getObject(any(GetObjectRequest.class)))
            .willReturn(stream("{\"argumentTexts\":{},\"expectedReturnText\":\"1\"}".getBytes(StandardCharsets.UTF_8)));
        var client = new TestAssetClient(s3, new TestDataProperties("bucket"), JsonMapper.builder().build());
        assertThatThrownBy(() -> client.read(new Asset("key", "v1", "a".repeat(64))))
            .isInstanceOf(JudgeException.class);
    }

    /** 실제 HTTP 본문이 멈춰도 제한 시간 안에 호출자가 실패를 받고 연결을 정리하는지 확인한다. */
    @Test
    @DisplayName("S3 본문 읽기가 멈추면 전체 읽기 기한 안에 실패한다")
    void timesOutWhileReadingBody() throws Exception {
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 10000);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                started.countDown();
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try (
            var actual
                = S3Client
                    .builder()
                    .region(Region.US_EAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                    .endpointOverride(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                    .forcePathStyle(true)
                    .build()
        ) {
            var client
                = new TestAssetClient(
                    actual,
                    new TestDataProperties("bucket"),
                    JsonMapper.builder().build(),
                    Duration.ofSeconds(1)
                );
            long before = System.nanoTime();
            assertThatThrownBy(() -> client.read(new Asset("key", "v1", "a".repeat(64))))
                .isInstanceOf(JudgeException.class);
            assertThat(started.getCount()).isZero();
            assertThat(Duration.ofNanos(System.nanoTime() - before))
                .isGreaterThanOrEqualTo(Duration.ofSeconds(1))
                .isLessThan(Duration.ofSeconds(4));
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    /**
     * 테스트 원문을 담은 응답 스트림을 만든다.
     *
     * @param bytes 응답 본문
     * @return S3 응답 스트림
     */
    private ResponseInputStream<GetObjectResponse> stream(byte[] bytes) {
        return new ResponseInputStream<>(
            GetObjectResponse.builder().build(),
            AbortableInputStream.create(new ByteArrayInputStream(bytes))
        );
    }
}
