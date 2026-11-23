package com.backtoback.study.replica.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.study.recommendation.domain.StudyRecommendation;
import com.backtoback.study.recommendation.repository.StudyRecommendationRepository;
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

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(30);

    // 다른 연결의 실행 중 쿼리(information_schema.PROCESSLIST)를 보려면 PROCESS 권한이 필요해 root로 접속한다.
    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer MYSQL
        = new MySQLContainer("mysql:8.4").withDatabaseName("study_db").withUsername("root").withPassword("test");

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
    private StudyRecommendationRepository recommendationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

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
        List<MemberLearningProfileReplica> replicas = repository.findAllByMemberIdIn(List.of(memberId));
        assertThat(replicas).hasSize(1);
        assertThat(replicas.get(0).getProfileVersion()).isEqualTo(1L);
        assertThat(processedCount(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 트랜잭션이 같은 소비 기록을 커밋하기 전에 INSERT가 충돌하면 대기 후 롤백하고 커밋된 기록을 확인해 성공 처리한다")
    void onMessage_insertCollidesWithUncommittedRecord_succeedsAfterCommit() throws Exception {
        // given — 같은 이벤트를 먼저 처리 중인 트랜잭션: 소비 기록만 INSERT하고 커밋을 멈춘다
        String eventId = "7d2c3b4a-5e6f-4a70-8b9c-0d1e2f3a4b5c";
        long memberId = 9202L;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch recordInserted = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        Future<?> firstDelivery = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate
                .update(
                    "INSERT INTO processed_events (event_id, event_type, processed_at, created_at) "
                        + "VALUES (?, 'LearningProfileUpdated', NOW(6), NOW(6))",
                    eventId
                );
            recordInserted.countDown();
            awaitUninterruptibly(releaseCommit);
        }));
        assertThat(recordInserted.await(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();

        // when — 두 번째 수신의 INSERT가 커밋되지 않은 같은 키에서 멈춘 것을 확인한 뒤 앞 트랜잭션을 커밋한다
        Future<?> secondDelivery = executor.submit(() -> listener.onMessage(message(eventId, memberId)));
        awaitBlockedInsert();
        assertThat(secondDelivery.isDone()).isFalse();
        releaseCommit.countDown();
        firstDelivery.get(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        // then — 충돌한 수신은 예외 없이 끝나고, 업무 반영은 롤백되어 복제본이 생기지 않는다
        assertThatCode(() -> secondDelivery.get(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).doesNotThrowAnyException();
        executor.shutdown();
        assertThat(repository.findAllByMemberIdIn(List.of(memberId))).isEmpty();
        assertThat(processedCount(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("학습 프로필 이벤트를 소비하면 같은 트랜잭션에서 해당 회원 추천을 즉시 재계산해 저장한다")
    void onMessage_profileUpdated_refreshesRecommendations() {
        // given
        long memberId = 9203L;
        jdbcTemplate
            .update(
                "INSERT INTO studies (name, goal, skill_level, preferred_language, active_time_slot, capacity, "
                    + "member_count, manager_count, join_type, is_auto_reopen, status, version, "
                    + "created_at, updated_at) "
                    + "VALUES ('즉시 재계산', 'EMPLOYMENT', 'LV2', 'java', 'EVENING', 10, 2, 0, 'APPROVAL', true, "
                    + "'RECRUITING', 0, NOW(6), NOW(6))"
            );
        Long studyId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM studies", Long.class);

        // when
        listener.onMessage(message("6c1b2a39-4d5e-4f60-8a7b-9c0d1e2f3a4b", memberId));

        // then
        assertThat(recommendationRepository.findAllByMemberId(memberId))
            .extracting(StudyRecommendation::getStudyId)
            .containsExactly(studyId);
    }

    private void awaitBlockedInsert() throws InterruptedException {
        Instant deadline = Instant.now().plus(WAIT_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            Integer executing
                = jdbcTemplate
                    .queryForObject(
                        "SELECT COUNT(*) FROM information_schema.PROCESSLIST "
                            + "WHERE COMMAND = 'Query' AND INFO LIKE 'insert into processed_events%'",
                        Integer.class
                    );
            if (executing != null && executing > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("두 번째 수신의 소비 기록 INSERT가 실행 중 상태로 확인되지 않았다");
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("커밋 해제 신호를 받지 못했다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("커밋 대기 중 인터럽트됐다", e);
        }
    }

    private Integer processedCount(String eventId) {
        return jdbcTemplate
            .queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id = ?", Integer.class, eventId);
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
