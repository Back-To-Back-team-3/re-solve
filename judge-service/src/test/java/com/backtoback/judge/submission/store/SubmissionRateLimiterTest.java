package com.backtoback.judge.submission.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.backtoback.judge.global.exception.custom.SubmissionRateLimitedException;

/** 실제 Redis에서 동시 요청·두 한도·TTL·회원 격리를 확인한다. */
@Testcontainers(disabledWithoutDocker = true)
class SubmissionRateLimiterTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private SubmissionRateLimiter limiter;

    /** 테스트 Redis 연결을 생성한다. */
    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
    }

    /** 테스트 연결을 종료한다. */
    @AfterAll
    static void close() {
        if (factory != null) {
            factory.destroy();
        }
    }

    /** 격리한 테스트 컨테이너의 요청 기록을 초기화한다. */
    @BeforeEach
    void reset() {
        try (var connection = factory.getConnection()) {
            connection.serverCommands().flushDb();
        }
        limiter = new SubmissionRateLimiter(redis);
    }

    /** 두 한도의 거절과 회원·문제별 격리를 확인한다. */
    @Test
    @DisplayName("동일 문제 5초 제한은 Retry-After를 반환하고 다른 회원·문제는 허용한다")
    void rejectsSameProblem() {
        limiter.acquire(7, 301);
        assertThatThrownBy(() -> limiter.acquire(7, 301))
            .isInstanceOfSatisfying(SubmissionRateLimitedException.class, exception -> {
                assertThat(exception.getErrorCode().getCode()).isEqualTo("SUBMISSION_RATE_LIMITED");
                assertThat(Long.parseLong(exception.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER)))
                    .isBetween(1L, 5L);
            });
        limiter.acquire(8, 301);
        limiter.acquire(7, 302);
        assertThat(redis.opsForZSet().zCard("submission:{7}:minute")).isEqualTo(2);
        assertThat(redis.getExpire("submission:{7}:problem:301")).isBetween(0L, 5L);
    }

    /**
     * 동시에 도착한 20건 중 10건만 허용한다.
     *
     * @throws Exception 동시 요청 실행 실패
     */
    @Test
    @DisplayName("분당 한도는 서로 다른 문제의 동시 20건 중 정확히 10건만 허용한다")
    void limitsConcurrentRequests() throws Exception {
        var start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(20)) {
            for (int index = 0; index < 20; index++) {
                long problem = index + 1;
                results.add(executor.submit(() -> {
                    start.await();
                    try {
                        limiter.acquire(7, problem);
                        return true;
                    } catch (SubmissionRateLimitedException exception) {
                        assertThat(exception.getErrorCode().getCode()).isEqualTo("SUBMISSION_RATE_LIMITED");
                        assertThat(Long.parseLong(exception.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER)))
                            .isBetween(1L, 60L);
                        return false;
                    }
                }));
            }
            start.countDown();
            int accepted = 0;
            for (var result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    accepted++;
                }
            }
            assertThat(accepted).isEqualTo(10);
        }
        assertThat(redis.opsForZSet().zCard("submission:{7}:minute")).isEqualTo(10);
    }

    /** 만료된 기록이 다음 요청을 계속 막지 않는지 확인한다. */
    @Test
    @DisplayName("만료된 분당 기록과 문제 쿨다운은 새 요청을 허용한다")
    void releasesExpiredRecords() {
        redis.opsForZSet().add("submission:{7}:minute", "old", 0);
        redis.opsForValue().set("submission:{7}:problem:301", "1");
        redis.expire("submission:{7}:problem:301", Duration.ZERO);
        limiter.acquire(7, 301);
        assertThat(redis.opsForZSet().zCard("submission:{7}:minute")).isEqualTo(1);
    }
}
