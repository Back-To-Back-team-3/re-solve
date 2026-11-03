package com.backtoback.contest.exam.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.event.dto.EventMetadata;
import com.backtoback.common.event.store.JdbcOutboxStore;
import com.backtoback.common.event.store.JdbcProcessedEventStore;
import com.backtoback.contest.exam.domain.Exam;
import com.backtoback.contest.exam.domain.ExamMode;
import com.backtoback.contest.exam.domain.ExamProblem;
import com.backtoback.contest.exam.domain.ExamStatus;
import com.backtoback.contest.exam.service.StudyExamQueryService;

import jakarta.persistence.EntityManager;

/**
 * 빈 MySQL 스키마에 적용한 마이그레이션과 시험 및 공통 저장소를 함께 검증한다.
 */
@SpringBootTest(
    properties = {
        "spring.cloud.aws.sns.enabled=false",
        "spring.cloud.aws.sqs.enabled=false"
    }
)
@Testcontainers(disabledWithoutDocker = true)
class ExamStorageTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("contest_db");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private ExamRepository exams;

    @Autowired
    private ExamProblemRepository problems;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JdbcOutboxStore outbox;

    @Autowired
    private JdbcProcessedEventStore processed;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private StudyExamQueryService studyExamQuery;

    @Test
    @DisplayName("스터디별 시험을 ID 순으로 조회하고 다음 페이지에서 마감과 확정을 구분한다")
    void pagesStudyExams() {
        transactions.executeWithoutResult(status -> {
            var start = LocalDateTime.parse("2026-01-01T09:00:00");
            var first
                = exams
                    .saveAndFlush(Exam.create(98765L, 2L, "첫 시험", ExamMode.FIXED, start, start.plusHours(1), null, 2));
            exams.saveAndFlush(Exam.create(98766L, 2L, "다른 스터디", ExamMode.FIXED, start, start.plusHours(1), null, 1));
            var second
                = exams
                    .saveAndFlush(Exam.create(98765L, 2L, "둘째 시험", ExamMode.FIXED, start, start.plusHours(1), null, 3));
            jdbc.update("UPDATE exams SET status = 'CLOSED' WHERE id = ?", first.getId());
            jdbc.update("UPDATE exams SET status = 'FINALIZED' WHERE id = ?", second.getId());
            entityManager.clear();
            var page = studyExamQuery.getStudyExams("98765", null, 1);
            assertThat(page.hasMore()).isTrue();
            assertThat(page.items()).singleElement().satisfies(item -> {
                assertThat(item.examId()).isEqualTo(first.getId().toString());
                assertThat(item.status()).isEqualTo("CLOSED");
                assertThat(item.examRevision()).isEqualTo("2");
                assertThat(item.startsAt()).isEqualTo(OffsetDateTime.parse("2026-01-01T09:00:00+09:00"));
            });
            var next = studyExamQuery.getStudyExams("98765", page.nextCursor(), 1);
            assertThat(next.hasMore()).isFalse();
            assertThat(next.nextCursor()).isNull();
            assertThat(next.items()).singleElement().satisfies(item -> {
                assertThat(item.examId()).isEqualTo(second.getId().toString());
                assertThat(item.status()).isEqualTo("FINALIZED");
            });
            assertThat(studyExamQuery.getStudyExams("98767", null, 20).items()).isEmpty();
            assertThatThrownBy(() -> studyExamQuery.getStudyExams("98766", page.nextCursor(), 1))
                .isInstanceOf(com.backtoback.contest.global.error.ContestException.class);
            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("빈 데이터베이스에 V1부터 V12까지 순서대로 적용한다")
    void migratesEmptyDatabase() {
        List<String> versions
            = jdbc
                .queryForList(
                    "SELECT version FROM flyway_schema_history WHERE success = true "
                        + "AND version IS NOT NULL ORDER BY installed_rank",
                    String.class
                );
        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12");
        assertThat(jdbc.queryForList("SHOW TABLES", String.class))
            .contains(
                "outbox_events",
                "processed_events",
                "shedlock",
                "audit_logs",
                "member_replicas",
                "exams",
                "exam_problems",
                "exam_participants",
                "exam_participant_histories",
                "exam_drafts",
                "exam_submissions",
                "exam_problem_results"
            );
    }

    @Test
    @DisplayName("시험과 고정 문제를 감사 시각과 함께 저장하고 다시 조회한다")
    void savesAndReadsExamAndProblem() {
        transactions.executeWithoutResult(status -> {
            var exam = exams.saveAndFlush(newExam());
            var problem
                = problems.saveAndFlush(ExamProblem.pin(exam, 11L, 12L, "고정 문제", 3, new BigDecimal("100.00"), 1));
            var examId = exam.getId();
            var problemId = problem.getId();
            entityManager.clear();
            var storedExam = exams.findById(examId).orElseThrow();
            var storedProblem = problems.findById(problemId).orElseThrow();
            assertThat(storedExam.getStatus()).isEqualTo(ExamStatus.SCHEDULED);
            assertThat(storedExam.getMode()).isEqualTo(ExamMode.FIXED);
            assertThat(storedExam.getResultRevision()).isZero();
            assertThat(storedExam.getTotalScore()).isEqualByComparingTo("100.00");
            assertThat(storedExam.getCreatedAt()).isNotNull();
            assertThat(storedExam.getUpdatedAt()).isNotNull();
            assertThat(storedProblem.getExam().getId()).isEqualTo(examId);
            assertThat(storedProblem.getProblemRevisionId()).isEqualTo(12L);
            assertThat(storedProblem.getRevisionNumber()).isEqualTo(3);
            assertThat(storedProblem.getCreatedAt()).isNotNull();
            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("기본 배점과 결과 순번을 저장하고 지정한 배점도 유지한다")
    void savesDefaultAndCustomScores() {
        transactions.executeWithoutResult(status -> {
            var start = LocalDateTime.parse("2026-01-01T09:00:00");
            var end = start.plusMinutes(90);
            var defaultExam = exams.saveAndFlush(Exam.create(1L, 2L, "기본", ExamMode.FIXED, start, end, null, 1));
            var customExam
                = exams
                    .saveAndFlush(
                        Exam.create(1L, 2L, "변경", ExamMode.FIXED, start, end, null, 1, new BigDecimal("75.00"))
                    );
            var defaultId = defaultExam.getId();
            var customId = customExam.getId();
            entityManager.clear();
            var stored = exams.findById(defaultId).orElseThrow();
            assertThat(stored.getTotalScore()).isEqualByComparingTo("100.00");
            assertThat(stored.getResultRevision()).isZero();
            assertThat(exams.findById(customId).orElseThrow().getTotalScore()).isEqualByComparingTo("75.00");
            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("시험별 문제 중복과 존재하지 않는 시험 참조를 데이터베이스가 거절한다")
    void enforcesProblemConstraints() {
        Long examId = transactions.execute(status -> exams.saveAndFlush(newExam()).getId());
        try {
            insertProblem(examId, 21L);
            assertThatThrownBy(() -> insertProblem(examId, 21L)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insertProblem(-1L, 22L)).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.update("DELETE FROM exam_problems WHERE exam_id = ?", examId);
            jdbc.update("DELETE FROM exams WHERE id = ?", examId);
        }
    }

    @Test
    @DisplayName("공통 JDBC 저장소가 마이그레이션된 이벤트 테이블을 사용하고 중복을 거절한다")
    void usesCommonEventStores() {
        var id = UUID.randomUUID();
        var metadata
            = new EventMetadata(
                id,
                "ExamCreated",
                "v1",
                OffsetDateTime.parse("2026-01-01T09:00:00+09:00"),
                "1",
                "test-correlation"
            );
        var now = LocalDateTime.parse("2026-01-01T09:00:00");
        try {
            transactions.executeWithoutResult(status -> {
                outbox.insert("EXAM", metadata, "{}");
                processed.insert(metadata, now);
                assertThat(processed.exists(id)).isTrue();
                assertThat(outbox.lockNextPending(0)).get().satisfies(event -> {
                    assertThat(event.metadata().eventId()).isEqualTo(id);
                    assertThat(event.retryCount()).isZero();
                });
            });
            assertThatThrownBy(() -> transactions.executeWithoutResult(status -> processed.insert(metadata, now)))
                .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.update("DELETE FROM processed_events WHERE event_id = ?", id.toString());
            jdbc.update("DELETE FROM outbox_events WHERE event_id = ?", id.toString());
        }
    }

    @Test
    @DisplayName("공통 테이블은 감사 기록과 잠금을 저장하고 회원 복제본 중복을 거절한다")
    void enforcesCommonTableConstraints() {
        transactions.executeWithoutResult(status -> {
            jdbc.update("""
                INSERT INTO audit_logs (actor_id, action, target_type, target_id, before_state, after_state, reason,
                    created_at) VALUES (1, 'UPDATE', 'EXAM', '1', NULL, '{"status":"CLOSED"}', '사유',
                    '2026-01-01 09:00:00')
                """);
            jdbc.update("""
                INSERT INTO shedlock (name, lock_until, locked_at, locked_by)
                VALUES ('test-lock', '2026-01-01 09:00:00', '2026-01-01 09:00:00', 'test')
                """);
            jdbc.update("""
                INSERT INTO member_replicas (member_id, nickname, member_status, source_version, synced_at,
                    created_at, updated_at) VALUES (101, '회원', 'ACTIVE', 1, '2026-01-01 09:00:00',
                    '2026-01-01 09:00:00', '2026-01-01 09:00:00')
                """);
            assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO member_replicas (member_id, nickname, member_status, source_version, synced_at,
                    created_at, updated_at) VALUES (101, '회원', 'ACTIVE', 1, '2026-01-01 09:00:00',
                    '2026-01-01 09:00:00', '2026-01-01 09:00:00')
                """)).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE target_id = '1'", Integer.class))
                .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shedlock WHERE name = 'test-lock'", Integer.class))
                .isEqualTo(1);
            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("활성 참가 중복을 막고 취소 후 재등록과 생성 컬럼을 확인한다")
    void enforcesActiveParticipantUniqueness() {
        transactions.executeWithoutResult(status -> {
            long examId = exams.saveAndFlush(newExam()).getId();
            insertParticipant(examId, 101L);
            assertThat(
                jdbc
                    .queryForObject(
                        "SELECT active_member_id FROM exam_participants WHERE exam_id = ?",
                        Long.class,
                        examId
                    )
            ).isEqualTo(101L);
            assertThatThrownBy(() -> insertParticipant(examId, 101L))
                .isInstanceOf(DataIntegrityViolationException.class);
            jdbc.update("UPDATE exam_participants SET status = 'CANCELED' WHERE exam_id = ?", examId);
            insertParticipant(examId, 101L);
            assertThat(
                jdbc
                    .queryForObject(
                        "SELECT COUNT(*) FROM exam_participants WHERE exam_id = ? " + "AND active_member_id IS NULL",
                        Integer.class,
                        examId
                    )
            ).isEqualTo(1);
            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("참가 이력과 Draft, 제출, 결과의 내부 참조와 복합 유일 제약을 확인한다")
    void enforcesExamChildConstraints() {
        transactions.executeWithoutResult(status -> {
            long examId = exams.saveAndFlush(newExam()).getId();
            insertParticipant(examId, 102L);
            long participantId
                = jdbc.queryForObject("SELECT id FROM exam_participants WHERE exam_id = ?", Long.class, examId);
            jdbc
                .update(
                    "INSERT INTO exam_participant_histories (exam_participant_id, to_status, created_at) "
                        + "VALUES (?, 'REGISTERED', NOW(6))",
                    participantId
                );
            assertThatThrownBy(
                () -> jdbc
                    .update(
                        "INSERT INTO exam_participant_histories "
                            + "(exam_participant_id, to_status, created_at) VALUES (-1, 'REGISTERED', NOW(6))"
                    )
            ).isInstanceOf(DataIntegrityViolationException.class);
            String draft
                = "INSERT INTO exam_drafts (exam_participant_id, problem_id, language, source_code, seq, "
                    + "saved_at, version, created_at, updated_at) "
                    + "VALUES (?, 1, 'JAVA', 'code', 1, NOW(6), 0, NOW(6), NOW(6))";
            jdbc.update(draft, participantId);
            assertThatThrownBy(() -> jdbc.update(draft, participantId))
                .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(draft, -1)).isInstanceOf(DataIntegrityViolationException.class);
            String submission
                = "INSERT INTO exam_submissions (exam_participant_id, exam_id, problem_id, draft_seq, "
                    + "language, source_code, submission_type, received_at, status, is_zero_confirmed, "
                    + "created_at, updated_at) "
                    + "VALUES (?, ?, 1, 1, 'JAVA', 'code', 'MANUAL', NOW(6), 'ACCEPTED', false, NOW(6), NOW(6))";
            jdbc.update(submission, participantId, examId);
            assertThatThrownBy(() -> jdbc.update(submission, participantId, examId))
                .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(submission, -1, examId))
                .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(submission, participantId, -1))
                .isInstanceOf(DataIntegrityViolationException.class);
            long submissionId
                = jdbc
                    .queryForObject(
                        "SELECT id FROM exam_submissions WHERE exam_participant_id = ?",
                        Long.class,
                        participantId
                    );
            String result
                = "INSERT INTO exam_problem_results (exam_participant_id, problem_id, last_valid_exam_submission_id, "
                    + "score, version, created_at, updated_at) VALUES (?, 1, ?, 0, 0, NOW(6), NOW(6))";
            jdbc.update(result, participantId, submissionId);
            assertThatThrownBy(() -> jdbc.update(result, participantId, submissionId))
                .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(result, -1, submissionId))
                .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(result, participantId, -1))
                .isInstanceOf(DataIntegrityViolationException.class);
            status.setRollbackOnly();
        });
    }

    private void insertParticipant(long examId, long memberId) {
        jdbc
            .update(
                "INSERT INTO exam_participants (exam_id, member_id, status, registered_at, personal_ends_at, "
                    + "total_score, is_ranked, version, created_at, updated_at) "
                    + "VALUES (?, ?, 'REGISTERED', NOW(6), NOW(6), 0, false, 0, NOW(6), NOW(6))",
                examId,
                memberId
            );
    }

    private Exam newExam() {
        return Exam
            .create(
                1L,
                2L,
                "시험",
                ExamMode.FIXED,
                LocalDateTime.parse("2026-01-01T09:00:00"),
                LocalDateTime.parse("2026-01-01T10:30:00"),
                null,
                1,
                new BigDecimal("100.00")
            );
    }

    private void insertProblem(Long examId, Long problemId) {
        jdbc.update("""
            INSERT INTO exam_problems (exam_id, problem_id, problem_revision_id, problem_title, revision_number,
                score, display_order, created_at, updated_at)
            VALUES (?, ?, 1, '문제', 1, 100.00, 1, '2026-01-01 09:00:00', '2026-01-01 09:00:00')
            """, examId, problemId);
    }
}
