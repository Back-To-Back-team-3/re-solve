package com.backtoback.study.hint.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;

/**
 * 힌트 일일 한도 동시성 통합 테스트.
 * 기본적으로 비활성화되어 있으며, 환경변수 RUN_CONCURRENCY_TESTS=true 로 활성화한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(
    named = "RUN_CONCURRENCY_TESTS",
    matches = "true"
)
class HintDailyLimitConcurrencyTest {

    private static final long MEMBER_ID = 9999L;
    private static final int ATTEMPT_COUNT = 21;

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("study_db");

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private HintDailyLimitService hintDailyLimitService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("동일 회원이 21개를 동시 요청하면 20개만 성공하고 1개는 일일 한도 오류가 발생한다")
    void reserve_concurrent_limitTwentyPerDay() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(ATTEMPT_COUNT);
        CountDownLatch ready = new CountDownLatch(ATTEMPT_COUNT);
        CountDownLatch start = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger limitExceededCount = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < ATTEMPT_COUNT; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    new TransactionTemplate(transactionManager).execute(status -> {
                        hintDailyLimitService.reserve(MEMBER_ID);
                        return null;
                    });
                    successCount.incrementAndGet();
                } catch (StudyException e) {
                    if (e.getErrorCode() == StudyErrorCode.HINT_DAILY_LIMIT_EXCEEDED) {
                        limitExceededCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        ready.await();
        start.countDown();
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception ignored) {
                // 개별 결과는 AtomicInteger로 집계
            }
        }
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(HintDailyLimitService.DAILY_LIMIT);
        assertThat(limitExceededCount.get()).isEqualTo(ATTEMPT_COUNT - HintDailyLimitService.DAILY_LIMIT);
    }
}
