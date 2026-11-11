package com.backtoback.judge.judging.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.event.config.OutboxStorageConfiguration;
import com.backtoback.judge.global.config.PersistenceConfiguration;
import com.backtoback.judge.judging.dto.SubmissionJudgingDataResponse;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 실제 MySQL에서 최신 회차 재검증과 대사·Outbox의 원자성을 확인한다. */
@DataJpaTest(properties = "spring.jpa.show-sql=false")
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    {
        PersistenceConfiguration.class,
        OutboxStorageConfiguration.class,
        SubmissionExecutionStore.class,
        SubmissionReconciliationStoreTest.Beans.class
    }
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class SubmissionReconciliationStoreTest {
    @Container
    static final MySQLContainer MYSQL
        = new MySQLContainer("mysql:8.4").withCommand("--log-bin-trust-function-creators=1");
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SubmissionExecutionStore store;
    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * 테스트별 독립 스키마에 연결한다.
     *
     * @param registry DB 연결 속성
     */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    /** 3분 이상 갱신되지 않은 제출과 최신 회차 작업을 준비한다. */
    @BeforeEach
    void prepare() {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_reconciliation_outbox");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM judge_test_results");
        jdbc.update("DELETE FROM judge_job_runs");
        jdbc.update("DELETE FROM judge_jobs");
        jdbc.update("DELETE FROM submissions");
        jdbc.update("""
                INSERT INTO submissions(id,member_id,problem_id,problem_revision_id,context,idempotency_key,
            request_hash,language,source_code,
                    code_size_bytes,status,judge_attempt,submitted_at,created_at,updated_at)
                VALUES (1,1,1,1,'PRACTICE','key',REPEAT('a',64),'java','x',1,'JUDGING',1,
                    '2026-10-31 09:00:00','2026-10-31 09:00:00','2026-10-31 09:00:00')
                """);
        jdbc.update("""
            INSERT INTO judge_jobs(id,submission_id,judge_attempt,status,try_count,unknown_retry_count,
                next_run_at,deadline_at,created_at,updated_at)
            VALUES (1,1,1,'RETRY_WAITING',2,0,'2026-10-31 09:01:00','2026-10-31 09:10:00',
                '2026-10-31 09:00:00','2026-10-31 09:00:00')
            """);
    }

    /** 동일 후보를 반복 처리해도 최초 보정만 반영한다. */
    @Test
    @DisplayName("대기 작업과 다른 진행 제출은 한 번만 QUEUED로 보정한다")
    void restoresWaitingSubmissionOnce() {
        var candidate = store.reconciliationCandidates().getFirst();
        assertThat(store.reconcile(candidate, null)).isTrue();
        assertThat(store.reconcile(candidate, null)).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM submissions", String.class)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("SELECT try_count FROM judge_jobs", Integer.class)).isEqualTo(2);
    }

    /** 완료 작업의 테스트 결과로 제출을 보정하고 실행 이력을 추가하지 않는다. */
    @Test
    @DisplayName("성공 작업 대사는 AC 결과와 이벤트를 한 번 반영하고 시도 이력을 늘리지 않는다")
    void restoresCompletedResultOnce() {
        jdbc.update("UPDATE judge_jobs SET status='SUCCEEDED',finished_at='2026-10-31 09:01:00' WHERE id=1");
        jdbc.update("""
            INSERT INTO judge_test_results(judge_job_id,test_asset_id,test_order,is_sample,token_status,
                verdict,time_ms,memory_kb,created_at,updated_at)
            VALUES (1,1,1,false,'DONE','AC',5,128,'2026-10-31 09:00:00','2026-10-31 09:00:00')
            """);
        var candidate = store.reconciliationCandidates().getFirst();
        var data
            = new SubmissionJudgingDataResponse(
                "1",
                "1",
                "제목",
                1,
                List.of("구현"),
                List.of("3"),
                null,
                null,
                List.of(),
                List.of()
            );
        assertThat(store.reconcile(candidate, data)).isTrue();
        assertThat(store.reconcile(candidate, data)).isFalse();
        assertThat(jdbc.queryForMap("SELECT status,verdict,passed_count FROM submissions"))
            .containsEntry("status", "COMPLETED")
            .containsEntry("verdict", "AC")
            .containsEntry("passed_count", 1);
        assertThat(jdbc.queryForObject("SELECT judged_at FROM submissions", java.time.LocalDateTime.class))
            .isEqualTo(java.time.LocalDateTime.of(2026, 10, 31, 9, 1));
        assertThat(jdbc.queryForObject("SELECT updated_at FROM submissions", java.time.LocalDateTime.class))
            .isEqualTo(java.time.LocalDateTime.of(2026, 10, 31, 9, 3));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM judge_job_runs", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT correlation_id FROM outbox_events", String.class))
            .startsWith("sched-JudgeReconciliation-");
    }

    /** 최신 회차나 정상 완료가 먼저 반영된 후보는 수정하지 않는다. */
    @Test
    @DisplayName("대상 선정 뒤 정상 완료되거나 최신 회차가 바뀌면 대사 후보를 폐기한다")
    void discardsChangedSnapshot() {
        var candidate = store.reconciliationCandidates().getFirst();
        jdbc.update("""
            UPDATE submissions SET status='COMPLETED',verdict='AC',passed_count=1,total_count=1,
                judged_at='2026-10-31 09:01:00' WHERE id=1
            """);
        assertThat(store.reconcile(candidate, null)).isFalse();
        jdbc.update("UPDATE submissions SET status='JUDGING',verdict=NULL,judge_attempt=2 WHERE id=1");
        assertThat(store.reconcile(candidate, null)).isFalse();
        assertThat(jdbc.queryForObject("SELECT judge_attempt FROM submissions", Integer.class)).isEqualTo(2);
    }

    /** 실패 보정과 이벤트 저장 중 하나라도 실패하면 제출 상태도 복원한다. */
    @Test
    @DisplayName("실패 대사의 Outbox 저장이 실패하면 제출 보정을 모두 롤백한다")
    void rollsBackWithOutbox() {
        jdbc.update("UPDATE judge_jobs SET status='FAILED',last_error='LEASE_EXHAUSTED' WHERE id=1");
        var candidate = store.reconciliationCandidates().getFirst();
        jdbc.execute("""
            CREATE TRIGGER reject_reconciliation_outbox BEFORE INSERT ON outbox_events
            FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='rejected'
            """);
        assertThatThrownBy(() -> store.reconcile(candidate, null)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM submissions", String.class)).isEqualTo("JUDGING");
        jdbc.execute("DROP TRIGGER reject_reconciliation_outbox");
        assertThat(store.reconcile(candidate, null)).isTrue();
        assertThat(store.reconcile(candidate, null)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(1);
    }

    /** 작업 누락을 자동 생성이나 임의 실패로 바꾸지 않는다. */
    @Test
    @DisplayName("최신 작업이 없으면 운영 확인 대상으로 탐지하고 상태를 보존한다")
    void detectsMissingJob() {
        jdbc.update("DELETE FROM judge_jobs");
        var candidate = store.reconciliationCandidates().getFirst();
        assertThat(candidate.jobId()).isNull();
        assertThat(store.missingReconciliationJob(candidate)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM submissions", String.class)).isEqualTo("JUDGING");
    }

    /**
     * 정상 실행기가 작업 잠금을 가진 동안 대사가 해당 행을 건너뛰는지 확인한다.
     *
     * @throws Exception 별도 연결의 대사 실행을 기다리지 못한 경우
     */
    @Test
    @DisplayName("정상 완료 트랜잭션이 작업을 잠근 동안 대사는 대기하거나 상태를 덮어쓰지 않는다")
    void skipsLockedCompletion() throws Exception {
        var candidate = store.reconciliationCandidates().getFirst();
        try (var executor = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.queryForList("SELECT id FROM judge_jobs WHERE id=1 FOR UPDATE");
                try {
                    assertThat(executor.submit(() -> store.reconcile(candidate, null)).get(5, TimeUnit.SECONDS))
                        .isFalse();
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
                jdbc.update("""
                    UPDATE submissions SET status='COMPLETED',verdict='AC',passed_count=1,total_count=1,
                        judged_at='2026-10-31 09:01:00' WHERE id=1
                    """);
            });
        }
        assertThat(store.reconcile(candidate, null)).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM submissions", String.class)).isEqualTo("COMPLETED");
    }

    /** 이전 구현이 남긴 재시도 제출도 정상 대기 상태로 정규화한다. */
    @Test
    @DisplayName("기존 RETRY_WAITING 제출은 대기 작업과 대사해 QUEUED로 보정한다")
    void normalizesLegacyRetryState() {
        jdbc.update("UPDATE submissions SET status='RETRY_WAITING' WHERE id=1");
        var candidate = store.reconciliationCandidates().getFirst();
        assertThat(store.reconcile(candidate, null)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM submissions", String.class)).isEqualTo("QUEUED");
    }

    @TestConfiguration
    static class Beans {
        /**
         * 고정 서비스 시각을 제공한다.
         *
         * @return 3분 경과한 시계
         */
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2026-10-31T00:03:00Z"), ZoneOffset.UTC);
        }

        /**
         * 이벤트 직렬화 매퍼를 제공한다.
         *
         * @return JSON 매퍼
         */
        @Bean
        ObjectMapper mapper() {
            return JsonMapper.builder().build();
        }
    }
}
