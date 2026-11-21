package com.backtoback.study.replica.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

import com.backtoback.study.membership.service.MemberWithdrawnConsumerService;
import com.backtoback.study.replica.event.dto.LearningProfileUpdatedPayload;
import com.backtoback.study.replica.event.dto.LearningProfileUpdatedPayload.TagLevel;
import com.backtoback.study.replica.event.dto.MemberWithdrawnPayload;
import com.backtoback.study.replica.repository.MemberLearningProfileReplicaRepository;
import com.backtoback.study.replica.repository.MemberReplicaRepository;

/**
 * 회원 탈퇴 소비와 학습 프로필 소비 경합 통합 테스트 (정책 및 상태 §9.4 회원 탈퇴 전파, event-contract.md §M3·§M4).
 * 기본적으로 비활성화되어 있으며, 환경변수 RUN_CONCURRENCY_TESTS=true 로 활성화한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(
    named = "RUN_CONCURRENCY_TESTS",
    matches = "true"
)
class MemberWithdrawalLearningProfileConcurrencyTest {

    private static final long FIRST_MEMBER_ID = 9300L;
    private static final int ROUNDS = 20;
    private static final int MAX_DELIVERIES = 3;
    private static final String WITHDRAWN_AT = "2026-11-21T10:00:00.000000+09:00";
    private static final LocalDateTime SYNCED_AT = LocalDateTime.of(2026, 11, 21, 9, 0);

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
    private MemberWithdrawnConsumerService withdrawnConsumer;

    @Autowired
    private LearningProfileReplicaConsumerService profileConsumer;

    @Autowired
    private MemberReplicaRepository memberReplicaRepository;

    @Autowired
    private MemberLearningProfileReplicaRepository learningProfileReplicaRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("탈퇴 이벤트와 학습 프로필 이벤트를 동시에 소비해도 탈퇴 회원의 학습 프로필 복제본이 남지 않는다")
    void consume_withdrawalAndProfileConcurrently_leavesNoProfileReplica() throws InterruptedException {
        List<Long> memberIds = new ArrayList<>();
        for (int round = 0; round < ROUNDS; round++) {
            long memberId = FIRST_MEMBER_ID + round;
            memberIds.add(memberId);
            // 절반은 회원 복제본이 있는 회원, 절반은 아직 없는 회원으로 둔다(행이 없을 때도 잠금으로 직렬화되는지 확인).
            if (round % 2 == 0) {
                transactionTemplate
                    .executeWithoutResult(
                        status -> memberReplicaRepository
                            .upsertFromMemberUpdated(memberId, "member" + memberId, null, "ACTIVE", 1L, SYNCED_AT)
                    );
            }
            runConcurrently(
                () -> withdrawnConsumer
                    .consume(
                        new MemberWithdrawnPayload(
                            UUID.randomUUID().toString(),
                            String.valueOf(memberId),
                            WITHDRAWN_AT,
                            100L
                        )
                    ),
                () -> profileConsumer.consume(profilePayload(memberId))
            );
        }

        assertThat(learningProfileReplicaRepository.findAllByMemberIdIn(memberIds)).isEmpty();
    }

    private void runConcurrently(Runnable first, Runnable second) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (Runnable task : List.of(first, second)) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    deliverWithRetry(task);
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
                // 결과는 마지막 복제본 상태로 판정한다
            }
        }
        executor.shutdown();
    }

    private void deliverWithRetry(Runnable task) {
        // 소비 실패(교착 등)는 운영에서 SQS 재수신으로 다시 처리되므로 같은 방식으로 재시도한다.
        for (int delivery = 1; delivery <= MAX_DELIVERIES; delivery++) {
            try {
                task.run();
                return;
            } catch (RuntimeException e) {
                if (delivery == MAX_DELIVERIES) {
                    throw e;
                }
            }
        }
    }

    private static LearningProfileUpdatedPayload profilePayload(long memberId) {
        return new LearningProfileUpdatedPayload(
            UUID.randomUUID().toString(),
            "2026-11-21T10:00:00.000000+09:00",
            "corr-race",
            String.valueOf(memberId),
            1L,
            "EMPLOYMENT",
            List.of("java"),
            List.of("EVENING"),
            List.of(new TagLevel("3", "LV2"))
        );
    }
}
