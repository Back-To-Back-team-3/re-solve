package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

import com.backtoback.common.web.logging.CorrelationIdFilter;
import com.backtoback.contest.exam.client.ExamDependencyClient;
import com.backtoback.contest.exam.client.ExamDependencyClient.ProblemSummaries;
import com.backtoback.contest.exam.client.ExamDependencyClient.ProblemSummary;
import com.backtoback.contest.exam.client.ExamDependencyClient.StudyMember;
import com.backtoback.contest.exam.client.ExamDependencyClient.StudyMembers;
import com.backtoback.contest.exam.dto.ExamCancelRequest;
import com.backtoback.contest.exam.dto.ExamCreateRequest;
import com.backtoback.contest.exam.dto.ExamCreateRequest.Problem;
import com.backtoback.contest.exam.dto.ExamUpdateRequest;
import com.backtoback.contest.exam.repository.ContestResourceGuardRepository;
import com.backtoback.contest.exam.repository.ContestResourceGuardRepository.Resource;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

/** MySQL 잠금과 Outbox를 사용해 시험 설정의 원자성과 경합을 확인한다. */
@SpringBootTest(
    properties = {
        "spring.cloud.aws.sns.enabled=false",
        "spring.cloud.aws.sqs.enabled=false"
    }
)
@Testcontainers(disabledWithoutDocker = true)
class ExamManagementIntegrationTest {
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
    private ExamDependencyClient dependencies;
    @MockitoBean
    private MemberReplicaService members;
    @Autowired
    private ExamManagementService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private ContestResourceGuardRepository guards;
    private OffsetDateTime start;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM exam_participants");
        jdbc.update("DELETE FROM exam_problems");
        jdbc.update("DELETE FROM exams");
        jdbc.update("DELETE FROM contest_resource_guards");
        start = OffsetDateTime.now().plusDays(2).withNano(0);
        given(members.refresh(7)).willReturn("ACTIVE");
        given(dependencies.study(12))
            .willReturn(new StudyMembers("12", "ACTIVE", List.of(new StudyMember("7", "8", "LEADER"))));
        given(dependencies.problems(List.of(1L)))
            .willReturn(
                new ProblemSummaries(
                    List.of(new ProblemSummary("1", true, "GENERAL", "PUBLIC", 3L, "10", 2, "문제", List.of("java")))
                )
            );
    }

    @Test
    @DisplayName("설정 수정은 문제 배점과 참가자 마감 및 전체 이벤트를 함께 저장한다")
    void updatesProblemsParticipantsAndOutbox() {
        var created = service.create("12", "7", createRequest());
        long examId = Long.parseLong(created.examId());
        jdbc
            .update(
                "INSERT INTO exam_participants (exam_id, member_id, status, registered_at, personal_ends_at, "
                    + "total_score, is_ranked, version, created_at, updated_at) VALUES (?, 7, 'REGISTERED', NOW(6), "
                    + "NOW(6), 0, false, 0, NOW(6), NOW(6))",
                examId
            );
        var result = service.update(created.examId(), "7", updateRequest(created.version()));
        assertThat(result.version()).isEqualTo("1");
        assertThat(result.examRevision()).isEqualTo("2");
        var scores = jdbc.queryForList("SELECT score FROM exam_problems WHERE exam_id = ?", BigDecimal.class, examId);
        assertThat(scores).containsExactly(new BigDecimal("90.00"));
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT personal_ends_at FROM exam_participants WHERE exam_id = ?",
                    java.time.LocalDateTime.class,
                    examId
                )
        )
            .isEqualTo(
                result
                    .endsAt()
                    .atZoneSameInstant(com.backtoback.common.time.ClockConfiguration.SERVICE_ZONE)
                    .toLocalDateTime()
            );
        String payload
            = jdbc
                .queryForObject(
                    "SELECT payload FROM outbox_events WHERE event_type = 'ExamUpdated' " + "AND aggregate_id = ?",
                    String.class,
                    created.examId()
                );
        assertThat(payload)
            .contains("\"examRevision\": \"2\"")
            .contains("\"targetMemberIds\": [\"7\"]")
            .contains("\"problemRevisionId\": \"10\"");
    }

    @Test
    @DisplayName("문제 숨김 트랜잭션이 먼저 확정되면 생성은 가드 잠금 후 비가용으로 거절한다")
    void rejectsWhenHideWinsGuard() throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var hide = executor.submit(() -> transactions.executeWithoutResult(status -> {
                guards.lockAll(List.of(new Resource("PROBLEM", 1)));
                jdbc
                    .update(
                        "UPDATE contest_resource_guards SET state='BLOCKED', source_scope='GENERAL', "
                            + "source_status='PRIVATE', source_version=4 "
                            + "WHERE resource_type='PROBLEM' AND resource_id=1"
                    );
                locked.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for test");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var create = executor.submit(() -> service.create("12", "7", createRequest()));
            try {
                assertThatThrownBy(() -> create.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            hide.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> create.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ContestException.class)
                .satisfies(
                    error -> assertThat(((ContestException)error.getCause()).getErrorCode())
                        .isEqualTo(ContestErrorCode.EXAM_PROBLEM_NOT_AVAILABLE)
                );
        }
    }

    @Test
    @DisplayName("Outbox 저장에 실패하면 시험과 참가자의 변경도 롤백한다")
    void rollsBackWhenOutboxFails() {
        var created = service.create("12", "7", createRequest());
        long examId = Long.parseLong(created.examId());
        jdbc
            .update(
                "INSERT INTO exam_participants (exam_id, member_id, status, registered_at, personal_ends_at, "
                    + "total_score, is_ranked, version, created_at, updated_at) VALUES (?, 7, 'REGISTERED', NOW(6), "
                    + "NOW(6), 0, false, 0, NOW(6), NOW(6))",
                examId
            );
        var before
            = jdbc
                .queryForObject(
                    "SELECT personal_ends_at FROM exam_participants WHERE exam_id = ?",
                    java.time.LocalDateTime.class,
                    examId
                );
        MDC.put(CorrelationIdFilter.MDC_KEY, "x".repeat(65));
        try {
            assertThatThrownBy(() -> service.update(created.examId(), "7", updateRequest(created.version())))
                .isInstanceOf(IllegalArgumentException.class);
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
        assertThat(jdbc.queryForObject("SELECT version FROM exams WHERE id = ?", Long.class, examId)).isZero();
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT personal_ends_at FROM exam_participants WHERE exam_id = ?",
                    java.time.LocalDateTime.class,
                    examId
                )
        ).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isZero();
    }

    @Test
    @DisplayName("시험 시작 트랜잭션이 먼저 확정되면 수정은 부모 잠금 후 거절한다")
    void rejectsWhenStartWinsParentLock() throws Exception {
        var created = service.create("12", "7", createRequest());
        long examId = Long.parseLong(created.examId());
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var starting = executor.submit(() -> transactions.executeWithoutResult(status -> {
                jdbc.update("UPDATE exams SET status='IN_PROGRESS', version=version+1 WHERE id=?", examId);
                locked.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for test");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var update = executor.submit(() -> service.update(created.examId(), "7", updateRequest(created.version())));
            try {
                assertThatThrownBy(() -> update.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            starting.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> update.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ContestException.class)
                .satisfies(
                    error -> assertThat(((ContestException)error.getCause()).getErrorCode())
                        .isEqualTo(ContestErrorCode.EXAM_MODIFICATION_CLOSED)
                );
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isZero();
        }
    }

    /** 취소 재요청은 이전 사유와 대상 스냅샷을 보존하고 이벤트를 중복 저장하지 않는다. */
    @Test
    @DisplayName("시험 취소 재요청은 기존 결과를 반환하고 참가와 이벤트를 보존한다")
    void cancellationIsIdempotent() {
        // given
        var created = service.create("12", "7", createRequest());
        jdbc
            .update(
                "INSERT INTO exam_participants (exam_id,member_id,status,participant_revision,registered_at,"
                    + "personal_ends_at,total_score,is_ranked,version,created_at,updated_at) "
                    + "VALUES (?,7,'REGISTERED',1,NOW(6),NOW(6),0,false,0,NOW(6),NOW(6))",
                created.examId()
            );

        // when
        var canceled = service.cancel(created.examId(), "7", new ExamCancelRequest(created.version(), "일정 변경"));
        var retried = service.cancel(created.examId(), "7", new ExamCancelRequest("0", "다른 사유"));

        // then
        assertThat(retried).isEqualTo(canceled);
        assertThat(canceled.status()).isEqualTo("CANCELED");
        assertThat(canceled.version()).isEqualTo("1");
        assertThat(jdbc.queryForObject("SELECT status FROM exam_participants", String.class)).isEqualTo("REGISTERED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(1);
        String payload
            = jdbc.queryForObject("SELECT payload FROM outbox_events WHERE event_type='ExamCanceled'", String.class);
        assertThat(payload).contains("\"reason\": \"일정 변경\"").contains("\"targetMemberIds\": [\"7\"]");
    }

    /** 버전 불일치와 시작 후 취소는 상태와 이벤트를 변경하지 않는다. */
    @Test
    @DisplayName("다른 버전 또는 이미 시작한 시험은 취소하지 않는다")
    void cancellationRejectsVersionAndStarted() {
        // given
        var created = service.create("12", "7", createRequest());
        // when & then
        assertThatThrownBy(() -> service.cancel(created.examId(), "7", new ExamCancelRequest("1", "사유")))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(ContestErrorCode.EXAM_REVISION_CONFLICT)
            );
        jdbc
            .update(
                "UPDATE exams SET starts_at=? WHERE id=?",
                java.time.LocalDateTime.now(com.backtoback.common.time.ClockConfiguration.SERVICE_ZONE).minusMinutes(1),
                created.examId()
            );
        assertThatThrownBy(() -> service.cancel(created.examId(), "7", new ExamCancelRequest("0", "사유")))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(ContestErrorCode.EXAM_MODIFICATION_CLOSED)
            );
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isZero();
    }

    /** 이벤트 저장 실패 시 취소 상태와 버전도 되돌린다. */
    @Test
    @DisplayName("시험 취소 Outbox 저장 실패는 시험 상태를 롤백한다")
    void cancellationRollsBackWithOutbox() {
        // given
        var created = service.create("12", "7", createRequest());
        MDC.put(CorrelationIdFilter.MDC_KEY, "x".repeat(65));
        // when & then
        try {
            assertThatThrownBy(() -> service.cancel(created.examId(), "7", new ExamCancelRequest("0", "사유")))
                .isInstanceOf(IllegalArgumentException.class);
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM exams WHERE id=?", String.class, created.examId()))
            .isEqualTo("SCHEDULED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isZero();
    }

    /** 시작 전이가 부모 잠금을 먼저 확정하면 취소는 최신 상태로 거절한다. */
    @Test
    @DisplayName("시작 전이가 먼저 커밋되면 대기한 시험 취소를 거절한다")
    void startWinsExamCancellation() throws Exception {
        // given
        var created = service.create("12", "7", createRequest());
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        // when & then
        try (var pool = Executors.newFixedThreadPool(2)) {
            var startTask = pool.submit(() -> transactions.executeWithoutResult(status -> {
                jdbc.update("UPDATE exams SET status='IN_PROGRESS', version=version+1 WHERE id=?", created.examId());
                locked.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var cancelTask = pool.submit(() -> service.cancel(created.examId(), "7", new ExamCancelRequest("0", "사유")));
            try {
                assertThatThrownBy(() -> cancelTask.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            startTask.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> cancelTask.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ContestException.class)
                .satisfies(
                    error -> assertThat(((ContestException)error.getCause()).getErrorCode())
                        .isEqualTo(ContestErrorCode.EXAM_MODIFICATION_CLOSED)
                );
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isZero();
    }

    private ExamCreateRequest createRequest() {
        return new ExamCreateRequest(
            "시험",
            "FIXED",
            start,
            start.plusMinutes(90),
            List.of(new Problem("1", "100.00", 1))
        );
    }

    private ExamUpdateRequest updateRequest(String version) {
        return new ExamUpdateRequest(
            version,
            "변경",
            start.plusHours(1),
            start.plusHours(2),
            List.of(new Problem("1", "90.00", 1))
        );
    }
}
