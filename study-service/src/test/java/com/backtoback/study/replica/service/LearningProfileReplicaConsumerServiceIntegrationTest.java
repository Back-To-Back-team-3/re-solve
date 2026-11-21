package com.backtoback.study.replica.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.study.replica.domain.MemberLearningProfileReplica;
import com.backtoback.study.replica.listener.SubmissionJudgedEventListener;
import com.backtoback.study.replica.repository.MemberLearningProfileReplicaRepository;

/**
 * 학습 프로필 변경 이벤트 중복 수신 통합 테스트 (event-contract.md §1.4 2 "유일 제약 위반이면 롤백 후 메시지만 삭제").
 * 소비 기록 저장소가 소비 트랜잭션에 참여하므로 mock이 아닌 실제 트랜잭션·MySQL로 수신부의 성공 처리를 확인한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class LearningProfileReplicaConsumerServiceIntegrationTest {

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
    private SubmissionJudgedEventListener listener;

    @Autowired
    private MemberLearningProfileReplicaRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("이미 커밋된 학습 프로필 이벤트를 다시 받으면 예외 없이 성공 처리하고 복제본과 소비 기록은 한 번만 남는다")
    void onMessage_sameEventTwice_secondSucceedsWithoutChange() {
        // given
        String eventId = "8b3e4d5c-6f70-4b82-8c9d-1e2f3a4b5c6d";
        long memberId = 9201L;
        String message = message(eventId, memberId);
        listener.onMessage(message);

        // when & then
        assertThatCode(() -> listener.onMessage(message)).doesNotThrowAnyException();
        assertSingleReplicaAndRecord(eventId, memberId);
    }

    @Test
    @DisplayName("같은 학습 프로필 이벤트를 동시에 두 번 받아도 둘 다 성공 처리하고 복제본과 소비 기록은 한 번만 남는다")
    void onMessage_sameEventConcurrently_bothSucceed() throws InterruptedException {
        // given
        String eventId = "7d2c3b4a-5e6f-4a70-8b9c-0d1e2f3a4b5c";
        long memberId = 9202L;
        String message = message(eventId, memberId);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>();

        // when
        for (int i = 0; i < 2; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    listener.onMessage(message);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
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

        // then
        assertThat(failures).isEmpty();
        assertSingleReplicaAndRecord(eventId, memberId);
    }

    private void assertSingleReplicaAndRecord(String eventId, long memberId) {
        List<MemberLearningProfileReplica> replicas = repository.findAllByMemberIdIn(List.of(memberId));
        assertThat(replicas).hasSize(1);
        assertThat(replicas.get(0).getProfileVersion()).isEqualTo(1L);
        Integer processed
            = jdbcTemplate
                .queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id = ?", Integer.class, eventId);
        assertThat(processed).isEqualTo(1);
    }

    private static String message(String eventId, long memberId) {
        return """
            {"eventId":"%s","eventType":"LearningProfileUpdated","schemaVersion":"v1",
             "occurredAt":"2026-11-21T10:00:00.123456+09:00","aggregateId":"%d","correlationId":"corr-dup",
             "memberId":"%d","profileVersion":1,"goal":"EMPLOYMENT","preferredLanguages":["java"],
             "activeTimeSlots":["EVENING"],"tagLevels":[{"tagId":"3","level":"LV2"}]}
            """.formatted(eventId, memberId, memberId);
    }
}
