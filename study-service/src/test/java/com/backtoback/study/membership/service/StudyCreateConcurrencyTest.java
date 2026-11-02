package com.backtoback.study.membership.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.membership.domain.JoinType;
import com.backtoback.study.membership.dto.StudyCreateRequest;
import com.backtoback.study.membership.repository.StudyMemberQuotaRepository;

/**
 * 스터디 생성 동시성 통합 테스트.
 * 기본적으로 비활성화되어 있으며, 환경변수 RUN_CONCURRENCY_TESTS=true 로 활성화한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(
    named = "RUN_CONCURRENCY_TESTS",
    matches = "true"
)
class StudyCreateConcurrencyTest {

    private static final long MEMBER_ID = 9001L;
    private static final int MAX_ACTIVE_STUDIES = 5;
    private static final int ATTEMPT_COUNT = 6;

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
    private StudyCommandService studyCommandService;

    @Autowired
    private StudyMemberQuotaRepository quotaRepository;

    private StudyCreateRequest request;

    @BeforeEach
    void setUp() {
        request
            = new StudyCreateRequest(
                "동시성 테스트 스터디",
                null,
                "EMPLOYMENT",
                "LV2",
                "java",
                "EVENING",
                5,
                JoinType.INSTANT,
                false
            );
    }

    @Test
    @DisplayName("동일 회원이 6개를 동시 생성하면 5개만 성공하고 1개는 참여 상한 오류가 발생한다")
    void createStudy_concurrent_limitFivePerMember() throws InterruptedException {
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
                    studyCommandService.createStudy(MEMBER_ID, request, "concurrency-test");
                    successCount.incrementAndGet();
                } catch (StudyException e) {
                    if (e.getErrorCode() == StudyErrorCode.STUDY_JOIN_LIMIT_EXCEEDED) {
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

        assertThat(successCount.get()).isEqualTo(MAX_ACTIVE_STUDIES);
        assertThat(limitExceededCount.get()).isEqualTo(1);

        // quota 행이 존재하고 active_count가 정확히 5인지 DB에서 확인
        assertThat(quotaRepository.findByMemberId(MEMBER_ID)).isPresent();
        quotaRepository
            .findByMemberId(MEMBER_ID)
            .ifPresent(quota -> assertThat(quota.getActiveCount()).isEqualTo(MAX_ACTIVE_STUDIES));
    }
}
