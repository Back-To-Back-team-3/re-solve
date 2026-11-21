package com.backtoback.study.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.study.recommendation.domain.StudyRecommendation;
import com.backtoback.study.recommendation.repository.StudyRecommendationRepository;
import com.backtoback.study.replica.domain.MemberLearningProfileReplica;
import com.backtoback.study.replica.repository.MemberLearningProfileReplicaRepository;

/**
 * 같은 회원의 추천 재계산 동시 실행 통합 테스트 (스케줄러 명세 5.4 경합 시나리오 — 회원 단위 교체 트랜잭션, 나중 커밋이 최종).
 * 기본적으로 비활성화되어 있으며, 환경변수 RUN_CONCURRENCY_TESTS=true 로 활성화한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(
    named = "RUN_CONCURRENCY_TESTS",
    matches = "true"
)
class StudyRecommendationRefreshConcurrencyTest {

    private static final long MEMBER_ID = 9400L;
    private static final int THREADS = 4;
    private static final int ROUNDS = 20;
    private static final int STUDIES = 5;

    @Container
    @SuppressWarnings("resource")
    // 운영 기본값이 READ COMMITTED여도 행 잠금만으로 직렬화되는지 확인하려고 서버 격리 수준을 READ COMMITTED로 둔다.
    static final MySQLContainer MYSQL
        = new MySQLContainer("mysql:8.4")
            .withDatabaseName("study_db")
            .withCommand("--transaction-isolation=READ-COMMITTED");

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private StudyRecommendationRefreshService refreshService;

    @Autowired
    private StudyRecommendationRepository recommendationRepository;

    @Autowired
    private MemberLearningProfileReplicaRepository learningProfileReplicaRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private final ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

    @Test
    @DisplayName("같은 회원의 재계산을 동시에 실행해도 교착·유일 키 충돌 없이 모두 성공하고 결과는 한 벌만 남는다")
    void refresh_sameMemberConcurrently_leavesSingleResultSet() throws InterruptedException {
        // given
        learningProfileReplicaRepository
            .save(
                MemberLearningProfileReplica
                    .sync(
                        MEMBER_ID,
                        "EMPLOYMENT",
                        "{\"3\": \"LV2\"}",
                        "[\"java\"]",
                        "[\"EVENING\"]",
                        1L,
                        LocalDateTime.of(2026, 11, 21, 9, 0)
                    )
            );
        for (int i = 0; i < STUDIES; i++) {
            jdbc
                .update(
                    "INSERT INTO studies (name, goal, skill_level, preferred_language, active_time_slot, capacity, "
                        + "member_count, manager_count, join_type, is_auto_reopen, status, version, "
                        + "created_at, updated_at) "
                        + "VALUES ('동시 재계산', 'EMPLOYMENT', 'LV2', 'java', 'EVENING', 10, 2, 0, 'APPROVAL', "
                        + "true, 'RECRUITING', 0, NOW(6), NOW(6))"
                );
        }

        // when
        for (int round = 0; round < ROUNDS; round++) {
            runConcurrently(() -> refreshService.refresh(MEMBER_ID));
        }

        // then
        assertThat(failures).isEmpty();
        List<StudyRecommendation> saved = recommendationRepository.findAllByMemberId(MEMBER_ID);
        assertThat(saved).hasSize(STUDIES);
        assertThat(saved).extracting(StudyRecommendation::getGeneratedAt).containsOnly(saved.get(0).getGeneratedAt());
    }

    private void runConcurrently(Runnable task) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    task.run();
                } catch (InterruptedException e) {
                    // 시작 신호 전에 인터럽트되면 재계산이 실행되지 않으므로 실패로 기록한다.
                    Thread.currentThread().interrupt();
                    failures.add(e);
                } catch (RuntimeException e) {
                    // 재시도 없이 교착·유일 키 충돌이 없어야 하므로 모든 예외를 실패로 모은다.
                    failures.add(e);
                }
            }));
        }
        ready.await();
        start.countDown();
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                failures.add(e);
            }
        }
        executor.shutdown();
    }
}
