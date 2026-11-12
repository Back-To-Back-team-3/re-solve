package com.backtoback.judge.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.event.config.OutboxStorageConfiguration;
import com.backtoback.judge.admin.dto.JudgeJobListRequest;
import com.backtoback.judge.global.config.PersistenceConfiguration;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.service.dto.SubmissionLease;
import com.backtoback.judge.judging.store.SubmissionExecutionStore;

/** 관리자 복구의 멱등 경합과 감사·실행 이력의 원자성을 실제 DB에서 확인한다. */
@DataJpaTest(properties = "spring.jpa.show-sql=false")
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    {
        PersistenceConfiguration.class,
        OutboxStorageConfiguration.class,
        SubmissionExecutionStore.class,
        JudgeJobOperationService.class,
        JudgeJobOperationServiceTest.Beans.class
    }
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class JudgeJobOperationServiceTest {
    @Container
    static final MySQLContainer MYSQL
        = new MySQLContainer("mysql:8.4").withCommand("--log-bin-trust-function-creators=1");
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JudgeJobOperationService operations;
    @Autowired
    private SubmissionExecutionStore executions;

    /**
     * 독립 MySQL 스키마를 제공한다.
     *
     * @param registry DB 연결 속성
     */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    /** 유효 임대와 미종결 제출을 가진 작업을 준비한다. */
    @BeforeEach
    void prepare() {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_recovery_audit");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM judge_recovery_requests");
        jdbc.update("DELETE FROM audit_logs");
        jdbc.update("DELETE FROM judge_test_results");
        jdbc.update("DELETE FROM judge_job_runs");
        jdbc.update("DELETE FROM judge_jobs");
        jdbc.update("DELETE FROM submissions");
        jdbc.update("""
            INSERT INTO submissions(id,member_id,problem_id,problem_revision_id,context,context_id,
                source_submission_id,language,source_code,code_size_bytes,status,judge_attempt,
                submitted_at,created_at,updated_at)
            VALUES (1,7,1,1,'EXAM',5,6,'java','secret-code',11,'JUDGING',1,
                '2026-10-31 09:00:00','2026-10-31 09:00:00','2026-10-31 09:00:00')
            """);
        jdbc.update("""
            INSERT INTO judge_jobs(id,submission_id,judge_attempt,status,try_count,unknown_retry_count,
                lease_token,lease_until,started_at,next_run_at,deadline_at,created_at,updated_at)
            VALUES (1,1,1,'RUNNING',1,0,'old','2026-10-31 09:05:00','2026-10-31 09:00:00',
                '2026-10-31 09:00:00','2026-10-31 09:10:00','2026-10-31 09:00:00','2026-10-31 09:00:00')
            """);
    }

    /**
     * 같은 키의 동시 복구가 시도와 감사 기록을 한 번만 증가시키는지 확인한다.
     *
     * @throws Exception 동시 요청을 제한시간 안에 완료하지 못한 경우
     */
    @Test
    @DisplayName("같은 키의 동시 복구는 최초 응답과 실행 이력·감사를 한 번만 저장한다")
    void serializesRecoveryAndKeepsResponse() throws Exception {
        var start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return operations.recover(1, 9, "key", "복구", "192.0.2.10");
            });
            var second = executor.submit(() -> {
                start.await();
                return operations.recover(1, 9, "key", "복구", "192.0.2.10");
            });
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo("RETRY_WAITING");
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo("RETRY_WAITING");
        }
        assertThat(jdbc.queryForObject("SELECT try_count FROM judge_jobs", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM judge_job_runs", Integer.class)).isEqualTo(1);
        var audit = jdbc.queryForMap("""
            SELECT before_state->>'$.status' AS before_status,before_state->>'$.tryCount' AS before_try,
                before_state->>'$.nextRunAt' AS before_next,before_state->>'$.leaseUntil' AS before_lease,
                after_state->>'$.status' AS after_status,after_state->>'$.tryCount' AS after_try,
                after_state->>'$.nextRunAt' AS after_next,JSON_EXTRACT(after_state,'$.leaseUntil') AS after_lease,
                client_ip FROM audit_logs
            """);
        assertThat(audit)
            .containsEntry("before_status", "RUNNING")
            .containsEntry("before_try", "1")
            .containsEntry("before_next", "2026-10-31 09:00:00.000000")
            .containsEntry("before_lease", "2026-10-31 09:05:00.000000")
            .containsEntry("after_status", "RETRY_WAITING")
            .containsEntry("after_try", "2")
            .containsEntry("after_next", "2026-10-31 09:03:00.000000")
            .containsEntry("after_lease", "null")
            .containsEntry("client_ip", "192.0.2.10");
        var lease = new SubmissionLease(1, 1, 1, 1, "old", LocalDateTime.of(2026, 10, 31, 9, 10));
        assertThat(executions.saveToken(lease, 1, "late-token")).isFalse();
        jdbc.update("UPDATE judge_jobs SET status='SUCCEEDED' WHERE id=1");
        assertThat(operations.recover(1, 9, "key", "복구", "192.0.2.11")).isEqualTo("RETRY_WAITING");
        assertThat(jdbc.queryForMap("SELECT client_ip FROM audit_logs")).containsEntry("client_ip", "192.0.2.10");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> operations.recover(1, 9, "key", "다른 사유", "192.0.2.10"))
            .isInstanceOf(JudgeException.class);
    }

    /** 감사 저장 실패는 상태와 멱등 기록을 함께 롤백한다. */
    @Test
    @DisplayName("감사 저장 실패는 복구·시도 이력·요청 기록을 모두 롤백한다")
    void rollsBackFailedAudit() {
        jdbc.execute("""
            CREATE TRIGGER reject_recovery_audit BEFORE INSERT ON audit_logs
            FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='rejected'
            """);
        assertThatThrownBy(() -> operations.recover(1, 9, "key", "복구", "192.0.2.10"))
            .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM judge_jobs", String.class)).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM judge_job_runs", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM judge_recovery_requests", Integer.class)).isZero();
    }

    /** 기한·시도 상한과 정상 종결을 우회할 수 없다. */
    @Test
    @DisplayName("시도 상한·기한 초과·이미 종결된 작업의 복구를 거절한다")
    void rejectsIneligibleJobs() {
        jdbc.update("UPDATE judge_jobs SET try_count=3 WHERE id=1");
        assertThatThrownBy(() -> operations.recover(1, 9, "key", "복구", "192.0.2.10"))
            .isInstanceOf(JudgeException.class);
        jdbc.update("UPDATE judge_jobs SET try_count=1,deadline_at='2026-10-31 09:03:00' WHERE id=1");
        assertThatThrownBy(() -> operations.recover(1, 9, "key", "복구", "192.0.2.10"))
            .isInstanceOf(JudgeException.class);
        jdbc.update("UPDATE judge_jobs SET status='SUCCEEDED' WHERE id=1");
        assertThatThrownBy(() -> operations.recover(1, 9, "key", "복구", "192.0.2.10"))
            .isInstanceOf(JudgeException.class);
    }

    /**
     * 관리자 복구와 완료가 경합해도 한쪽만 기존 임대를 종결한다.
     *
     * @throws Exception 동시 작업을 제한시간 안에 완료하지 못한 경우
     */
    @Test
    @DisplayName("관리자 복구와 정상 완료가 경합하면 한 경로만 기존 임대를 종결한다")
    void serializesRecoveryAndCompletion() throws Exception {
        jdbc.update("""
            INSERT INTO judge_test_results(judge_job_id,test_asset_id,test_order,is_sample,token_status,
                judge0_token,verdict,created_try_count,created_at,updated_at)
            VALUES (1,1,1,false,'DONE','token','AC',1,'2026-10-31 09:00:00','2026-10-31 09:00:00')
            """);
        var lease = new SubmissionLease(1, 1, 1, 1, "old", LocalDateTime.of(2026, 10, 31, 9, 10));
        var start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var completed = executor.submit(() -> {
                start.await();
                return executions.complete(lease, null);
            });
            var recovered = executor.submit(() -> {
                start.await();
                try {
                    operations.recover(1, 9, "race", "복구", "192.0.2.10");
                    return true;
                } catch (JudgeException exception) {
                    return false;
                }
            });
            boolean completionWon = completed.get(10, TimeUnit.SECONDS);
            boolean recoveryWon = recovered.get(10, TimeUnit.SECONDS);
            assertThat(completionWon).isNotEqualTo(recoveryWon);
            if (recoveryWon) {
                assertThat(executions.complete(lease, null)).isFalse();
                var next = executions.claim().orElseThrow();
                assertThat(executions.prepare(next)).isTrue();
                assertThat(executions.complete(next, null)).isTrue();
            }
        }
        assertThat(jdbc.queryForObject("SELECT status FROM submissions", String.class)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT verdict FROM submissions", String.class)).isEqualTo("AC");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT event_type FROM outbox_events", String.class))
            .isEqualTo("ExamSubmissionJudged");
    }

    /** 필터 목록과 상세 오류 정보를 안전하게 조회한다. */
    @Test
    @DisplayName("관리자 목록은 문맥 필터를 적용하고 상세는 외부 오류 원문을 가린다")
    void filtersAndSanitizes() {
        jdbc.update("UPDATE judge_jobs SET last_error='private upstream response' WHERE id=1");
        var page = operations.list(new JudgeJobListRequest(null, null, "5", null, 0, 20, "updatedAt,desc"));
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().getFirst().failureCode()).isEqualTo("SYSTEM_ERROR");
        assertThat(operations.detail(1).job().failureCode()).isEqualTo("SYSTEM_ERROR");
        assertThat(operations.list(new JudgeJobListRequest(null, null, "8", null, 0, 20, null))).isEmpty();
    }

    /** 이전 UNKNOWN은 유예가 지난 새 시도에서 정상 생성 가능 상태로 초기화한다. */
    @Test
    @DisplayName("수동 RUNNING 복구 뒤 이전 UNKNOWN을 정리하고 다음 시도의 실행 생성을 허용한다")
    void resumesUnknownAfterManualRecovery() {
        jdbc.update("""
            INSERT INTO judge_test_results(judge_job_id,test_asset_id,test_order,is_sample,token_status,
                created_try_count,created_at,updated_at)
            VALUES (1,1,1,false,'UNKNOWN',1,'2026-10-31 09:00:00','2026-10-31 09:02:00')
            """);
        operations.recover(1, 9, "key", "복구", "192.0.2.10");
        var next = executions.claim().orElseThrow();
        assertThat(next.tryCount()).isEqualTo(2);
        assertThat(executions.prepare(next)).isTrue();
        assertThat(executions.beginCreate(next, 1)).isTrue();
    }

    /**
     * 다른 멱등키도 같은 작업에서 FK 공유 잠금 승격 없이 직렬화되는지 확인한다.
     *
     * @throws Exception 동시 복구 요청을 제한시간 안에 완료하지 못한 경우
     */
    @Test
    @DisplayName("같은 작업에 서로 다른 키로 복구해도 교착 없이 작업 잠금 순서로 처리한다")
    void serializesDifferentKeys() throws Exception {
        var start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return operations.recover(1, 9, "first", "복구", "192.0.2.10");
            });
            var second = executor.submit(() -> {
                start.await();
                return operations.recover(1, 9, "second", "복구", "192.0.2.10");
            });
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo("RETRY_WAITING");
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo("RETRY_WAITING");
        }
        assertThat(jdbc.queryForObject("SELECT try_count FROM judge_jobs", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM judge_job_runs", Integer.class)).isEqualTo(1);
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class Beans {
        /**
         * 복구 검증에 고정 서비스 시각을 제공한다.
         *
         * @return 3분 경과한 서비스 시계
         */
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        java.time.Clock testClock() {
            return java.time.Clock.fixed(java.time.Instant.parse("2026-10-31T00:03:00Z"), java.time.ZoneOffset.UTC);
        }

        /**
         * 이벤트 직렬화 매퍼를 제공한다.
         *
         * @return 테스트 JSON 매퍼
         */
        @org.springframework.context.annotation.Bean
        tools.jackson.databind.ObjectMapper mapper() {
            return tools.jackson.databind.json.JsonMapper.builder().build();
        }
    }

}
