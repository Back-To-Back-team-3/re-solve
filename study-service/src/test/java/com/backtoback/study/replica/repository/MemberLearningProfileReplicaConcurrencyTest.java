package com.backtoback.study.replica.repository;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.study.replica.domain.MemberLearningProfileReplica;

/**
 * 학습 프로필 복제본 조건부 교체 동시성 통합 테스트 (event-contract.md §1.5, §5.1 순서 값 비교).
 * 기본적으로 비활성화되어 있으며, 환경변수 RUN_CONCURRENCY_TESTS=true 로 활성화한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(
    named = "RUN_CONCURRENCY_TESTS",
    matches = "true"
)
class MemberLearningProfileReplicaConcurrencyTest {

    private static final long MEMBER_ID = 9101L;
    private static final int ATTEMPT_COUNT = 8;
    private static final LocalDateTime SYNCED_AT = LocalDateTime.of(2026, 11, 21, 10, 0, 0);

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
    private MemberLearningProfileReplicaRepository repository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("복제본이 없는 회원의 서로 다른 버전 이벤트가 동시에 반영되면 행은 하나이고 성공한 가장 높은 버전만 남는다")
    void upsert_concurrentFirstEvents_keepsSingleRowWithHighestVersion() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(ATTEMPT_COUNT);
        CountDownLatch ready = new CountDownLatch(ATTEMPT_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Long> appliedVersions = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> interrupted = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 1; i <= ATTEMPT_COUNT; i++) {
            long version = i;
            futures.add(executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    transactionTemplate
                        .executeWithoutResult(
                            status -> repository
                                .upsertFromLearningProfileUpdated(
                                    MEMBER_ID,
                                    "GOAL_" + version,
                                    null,
                                    "[\"java\"]",
                                    null,
                                    version,
                                    SYNCED_AT
                                )
                        );
                    appliedVersions.add(version);
                } catch (InterruptedException e) {
                    // 시작 신호 전에 인터럽트되면 반영이 실행되지 않으므로 실패로 기록한다.
                    Thread.currentThread().interrupt();
                    interrupted.add(e);
                } catch (RuntimeException ignored) {
                    // 같은 키 동시 삽입의 교착 실패는 운영에서 SQS 재수신으로 다시 처리된다. 성공한 반영만 판정에 쓴다.
                }
            }));
        }
        ready.await();
        start.countDown();
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception ignored) {
                // 개별 결과는 appliedVersions로 집계
            }
        }
        executor.shutdown();

        List<MemberLearningProfileReplica> replicas = repository.findAllByMemberIdIn(List.of(MEMBER_ID));
        long highestApplied = appliedVersions.stream().mapToLong(Long::longValue).max().orElseThrow();
        assertThat(interrupted).isEmpty();
        assertThat(replicas).hasSize(1);
        assertThat(replicas.get(0).getProfileVersion()).isEqualTo(highestApplied);
        assertThat(replicas.get(0).getGoal()).isEqualTo("GOAL_" + highestApplied);
    }
}
