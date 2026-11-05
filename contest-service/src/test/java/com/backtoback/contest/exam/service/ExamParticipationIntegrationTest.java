package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.common.web.logging.CorrelationIdFilter;
import com.backtoback.contest.exam.repository.ContestResourceGuardRepository;
import com.backtoback.contest.exam.repository.ContestResourceGuardRepository.Resource;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

/** 참가 상태·이력·이벤트와 MySQL 경합을 검증한다. */
@SpringBootTest(
    properties = {
        "spring.cloud.aws.sns.enabled=false",
        "spring.cloud.aws.sqs.enabled=false"
    }
)
@Testcontainers(disabledWithoutDocker = true)
class ExamParticipationIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("contest_db");
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @MockitoBean
    private MemberReplicaService refresh;
    @Autowired
    private ExamParticipationService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private ContestResourceGuardRepository guards;
    private String examId;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM exam_participant_histories");
        jdbc.update("DELETE FROM exam_participants");
        jdbc.update("DELETE FROM exam_problems");
        jdbc.update("DELETE FROM exams");
        jdbc.update("DELETE FROM contest_resource_guards");
        jdbc.update("DELETE FROM member_replicas");
        jdbc.update("DELETE FROM study_membership_replicas");
        jdbc
            .update(
                "INSERT INTO member_replicas (member_id,nickname,member_status,source_version,"
                    + "synced_at,created_at,updated_at) " + "VALUES (7,'회원','ACTIVE',1,NOW(6),NOW(6),NOW(6))"
            );
        jdbc
            .update(
                "INSERT INTO study_membership_replicas (study_id,member_id,membership_id,is_active,"
                    + "synced_at,created_at,updated_at) " + "VALUES (12,7,1,true,NOW(6),NOW(6),NOW(6))"
            );
        jdbc
            .update(
                "INSERT INTO exams (study_id,creator_id,title,mode,starts_at,ends_at,status,"
                    + "exam_revision,total_score,"
                    + "result_revision,version,created_at,updated_at) VALUES (12,7,'시험','FIXED',"
                    + "DATE_ADD(NOW(6), INTERVAL 1 HOUR),"
                    + "DATE_ADD(NOW(6), INTERVAL 2 HOUR),'SCHEDULED',1,100,0,0,NOW(6),NOW(6))"
            );
        var now = java.time.LocalDateTime.now(ClockConfiguration.SERVICE_ZONE);
        jdbc.update("UPDATE exams SET starts_at=?, ends_at=?", now.plusHours(1), now.plusHours(2));
        examId = jdbc.queryForObject("SELECT MAX(id) FROM exams", String.class);
        jdbc
            .update(
                "INSERT INTO exam_problems (exam_id,problem_id,problem_revision_id,problem_title,"
                    + "revision_number,score,"
                    + "display_order,created_at,updated_at) VALUES (?,1,10,'문제',1,100,1,NOW(6),NOW(6))",
                examId
            );
        given(refresh.refresh(7)).willReturn("ACTIVE");
    }

    @Test
    @DisplayName("중복 등록과 취소 재요청은 이벤트를 중복 생성하지 않고 재등록은 새 참가를 생성한다")
    void registrationAndCancellationAreIdempotent() {
        var first = service.register(examId, "7");
        assertThat(first.created()).isTrue();
        assertThat(service.register(examId, "7").created()).isFalse();
        var canceled = service.cancel(examId, "7");
        assertThat(service.cancel(examId, "7")).isEqualTo(canceled);
        var second = service.register(examId, "7");
        assertThat(second.response().participantId()).isNotEqualTo(first.response().participantId());
        assertThat(count("exam_participant_histories")).isEqualTo(3);
        assertThat(count("outbox_events")).isEqualTo(3);
        String payload
            = jdbc
                .queryForObject(
                    "SELECT payload FROM outbox_events "
                        + "WHERE event_type='ExamParticipantRegistered' ORDER BY id LIMIT 1",
                    String.class
                );
        assertThat(payload).contains("participantId", "contextRevision", "problemIds", "startsAt", "endsAt");
    }

    @Test
    @DisplayName("스터디를 탈퇴한 기존 참가자는 입장하며 재입장은 최초 시각과 버전을 유지한다")
    void reentryAfterLeavingStudy() {
        service.register(examId, "7");
        jdbc.update("UPDATE study_membership_replicas SET is_active=false");
        jdbc
            .update(
                "UPDATE exams SET starts_at=? WHERE id=?",
                java.time.LocalDateTime.now(ClockConfiguration.SERVICE_ZONE).minusMinutes(1),
                examId
            );
        var entry = service.enter(examId, "7");
        var again = service.enter(examId, "7");
        assertThat(entry.enteredAt()).isEqualTo(again.enteredAt());
        assertThat(again.participantRevision()).isEqualTo("2");
        assertThat(count("exam_participant_histories")).isEqualTo(2);
    }

    @Test
    @DisplayName("동시 등록은 하나의 활성 참가와 등록 이벤트만 생성한다")
    void concurrentRegistration() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> {
                start.await();
                return service.register(examId, "7");
            });
            var two = pool.submit(() -> {
                start.await();
                return service.register(examId, "7");
            });
            start.countDown();
            var first = one.get(10, TimeUnit.SECONDS);
            var second = two.get(10, TimeUnit.SECONDS);
            assertThat(first.created()).isNotEqualTo(second.created());
            assertThat(first.response().participantId()).isEqualTo(second.response().participantId());
        }
        assertThat(count("exam_participants")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);
    }

    @Test
    @DisplayName("정지된 회원은 입장하지 못하고 시작 시각 후에는 참가를 취소하지 못한다")
    void suspensionAndCancellationBoundary() {
        service.register(examId, "7");
        jdbc.update("UPDATE member_replicas SET member_status='SUSPENDED' WHERE member_id=7");
        assertThatThrownBy(() -> service.enter(examId, "7"))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(ContestErrorCode.MEMBER_SUSPENDED)
            );
        jdbc
            .update(
                "UPDATE exams SET starts_at=? WHERE id=?",
                java.time.LocalDateTime.now(ClockConfiguration.SERVICE_ZONE).minusMinutes(1),
                examId
            );
        assertThatThrownBy(() -> service.cancel(examId, "7"))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(ContestErrorCode.EXAM_PARTICIPATION_CANCELLATION_CLOSED)
            );
    }

    @Test
    @DisplayName("등록 이벤트 저장이 실패하면 참가와 이력도 롤백한다")
    void outboxFailureRollsBack() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "x".repeat(65));
        try {
            assertThatThrownBy(() -> service.register(examId, "7")).isInstanceOf(IllegalArgumentException.class);
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
        assertThat(count("exam_participants")).isZero();
        assertThat(count("exam_participant_histories")).isZero();
        assertThat(count("outbox_events")).isZero();
    }

    @Test
    @DisplayName("탈퇴 가드 준비가 먼저 확정되면 대기한 입장을 차단한다")
    void withdrawalGuardWinsEntryRace() throws Exception {
        service.register(examId, "7");
        jdbc
            .update(
                "UPDATE exams SET starts_at=? WHERE id=?",
                java.time.LocalDateTime.now(ClockConfiguration.SERVICE_ZONE).minusMinutes(1),
                examId
            );
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var withdrawal = pool.submit(() -> transactions.executeWithoutResult(status -> {
                guards.lockAll(java.util.List.of(new Resource("MEMBER", 7)));
                jdbc
                    .update(
                        "UPDATE contest_resource_guards SET state='PREPARED' "
                            + "WHERE resource_type='MEMBER' AND resource_id=7"
                    );
                locked.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var entry = pool.submit(() -> service.enter(examId, "7"));
            try {
                assertThatThrownBy(() -> entry.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally {
                release.countDown();
            }
            withdrawal.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> entry.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ContestException.class)
                .satisfies(
                    error -> assertThat(((ContestException)error.getCause()).getErrorCode())
                        .isEqualTo(ContestErrorCode.CONTEST_OPERATION_BLOCKED)
                );
        }
        assertThat(jdbc.queryForObject("SELECT entered_at FROM exam_participants", java.time.LocalDateTime.class))
            .isNull();
    }

    @Test
    @DisplayName("시험 시작 변경이 부모 잠금을 먼저 잡으면 대기한 취소는 거절한다")
    void startWinsCancellationRace() throws Exception {
        service.register(examId, "7");
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var starting = pool.submit(() -> transactions.executeWithoutResult(status -> {
                jdbc
                    .update(
                        "UPDATE exams SET starts_at=? WHERE id=?",
                        java.time.LocalDateTime.now(ClockConfiguration.SERVICE_ZONE).minusMinutes(1),
                        examId
                    );
                locked.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var cancel = pool.submit(() -> service.cancel(examId, "7"));
            try {
                assertThatThrownBy(() -> cancel.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally {
                release.countDown();
            }
            starting.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> cancel.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ContestException.class)
                .satisfies(
                    error -> assertThat(((ContestException)error.getCause()).getErrorCode())
                        .isEqualTo(ContestErrorCode.EXAM_PARTICIPATION_CANCELLATION_CLOSED)
                );
        }
        assertThat(count("outbox_events")).isEqualTo(1);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
