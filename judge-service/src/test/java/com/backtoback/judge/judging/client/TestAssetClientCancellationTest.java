package com.backtoback.judge.judging.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.config.TestDataProperties;
import com.backtoback.judge.judging.dto.SubmissionJudgingDataResponse.Asset;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import tools.jackson.databind.json.JsonMapper;

/** 읽기 중단 시 MDC 전파와 응답 연결 해제를 검증한다. */
@ExtendWith(MockitoExtension.class)
class TestAssetClientCancellationTest {
    @Mock
    private S3Client s3;

    /**
     * 제한 시간이 지난 응답 연결을 중단하고 읽기 스레드에 MDC를 전달하는지 확인한다.
     *
     * @throws Exception 테스트 응답 대역 설정이나 대기 실패
     */
    @Test
    @DisplayName("본문 읽기 시간 초과 시 연결을 중단하고 S3 스레드에도 correlationId를 전달한다")
    void abortsTimedOutRead() throws Exception {
        var blocked = new BlockedBody();
        var observed = new AtomicReference<String>();
        given(s3.getObject(any(GetObjectRequest.class))).willAnswer(invocation -> {
            observed.set(MDC.get("correlationId"));
            return blocked.response();
        });
        var client
            = new TestAssetClient(
                s3,
                new TestDataProperties("bucket"),
                JsonMapper.builder().build(),
                Duration.ofSeconds(1)
            );
        String previous = MDC.get("correlationId");
        MDC.put("correlationId", "request-test");
        try {
            assertThatThrownBy(() -> client.read(new Asset("key", "v1", "a".repeat(64))))
                .isInstanceOf(JudgeException.class);
            assertThat(blocked.started.getCount()).isZero();
            assertThat(blocked.aborted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(observed.get()).isEqualTo("request-test");
            assertThat(MDC.get("correlationId")).isEqualTo("request-test");
        } finally {
            blocked.aborted.countDown();
            if (previous == null) {
                MDC.remove("correlationId");
            } else {
                MDC.put("correlationId", previous);
            }
        }
    }

    /**
     * 호출 스레드의 인터럽트가 자료 오류로 바뀌지 않고 연결을 해제하는지 확인한다.
     *
     * @throws Exception 테스트 응답 대역 설정이나 대기 실패
     */
    @Test
    @DisplayName("본문 읽기 중 인터럽트는 그대로 전달하고 연결을 중단한다")
    void propagatesInterrupt() throws Exception {
        var blocked = new BlockedBody();
        given(s3.getObject(any(GetObjectRequest.class))).willReturn(blocked.response());
        var client = new TestAssetClient(s3, new TestDataProperties("bucket"), JsonMapper.builder().build());
        var failure = new AtomicReference<Throwable>();
        var caller = Thread.startVirtualThread(() -> {
            try {
                client.read(new Asset("key", "v1", "a".repeat(64)));
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
        try {
            assertThat(blocked.started.await(3, TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
            caller.join(3000);
            assertThat(caller.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(InterruptedException.class);
            assertThat(blocked.aborted.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            blocked.aborted.countDown();
            caller.interrupt();
            caller.join(3000);
        }
    }

    /** 인터럽트만으로는 해제되지 않는 소켓 읽기를 재현한다. */
    private static class BlockedBody extends InputStream {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch aborted = new CountDownLatch(1);

        /** {@inheritDoc} */
        @Override
        public int read() throws IOException {
            started.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (aborted.getCount() != 0 && System.nanoTime() < deadline) {
                try {
                    if (aborted.await(100, TimeUnit.MILLISECONDS)) {
                        break;
                    }
                } catch (InterruptedException ignored) {
                    // 소켓 읽기가 인터럽트에 반응하지 않아도 abort로 해제되어야 한다.
                }
            }
            throw new IOException("연결이 중단되었습니다.");
        }

        /**
         * 연결 중단을 관찰할 수 있는 응답을 만든다.
         *
         * @return 중단 시 본문 읽기를 해제하는 S3 응답
         */
        private ResponseInputStream<GetObjectResponse> response() {
            return new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(this, aborted::countDown)
            );
        }
    }
}
