package com.backtoback.judge.judging.store;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.backtoback.judge.global.config.PersistenceConfiguration;

/** 실제 Judge DB 상태로 대기열 지표의 관측 전용 계약을 확인한다. */
@DataJpaTest(properties = "spring.jpa.show-sql=false")
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    {
        PersistenceConfiguration.class,
        OutboxStorageConfiguration.class,
        SubmissionExecutionStore.class,
        com.backtoback.judge.judging.service.JudgeQueueMetrics.class,
        SubmissionReconciliationStoreTest.Beans.class
    }
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class JudgeQueueMetricsStoreTest {
    @Container
    static final MySQLContainer MYSQL
        = new MySQLContainer("mysql:8.4").withCommand("--log-bin-trust-function-creators=1");
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private com.backtoback.judge.judging.service.JudgeQueueMetrics metrics;

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
        jdbc.update("DELETE FROM judge_recovery_requests");
        jdbc.update("DELETE FROM audit_logs");
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

    /** 관측이 업무 상태를 수정하거나 실패 건수를 중복 증가시키지 않는지 확인한다. */
    @Test
    @DisplayName("현재 대기·실패 지표는 상태 변경을 반영하고 반복 수집해도 누적 실패가 늘지 않는다")
    void observesWithoutMutating() {
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        try {
            metrics.bindTo(registry);
            assertThat(registry.get("judge.jobs.pending.count").gauge().value()).isZero();
            jdbc.update("UPDATE judge_jobs SET status='PENDING' WHERE id=1");
            assertThat(registry.get("judge.jobs.pending.count").gauge().value()).isEqualTo(1);
            assertThat(registry.get("scheduler.backlog.oldest.seconds").gauge().value()).isEqualTo(180);
            jdbc.update("UPDATE judge_jobs SET status='FAILED' WHERE id=1");
            assertThat(registry.get("judge.jobs.failed").functionCounter().count()).isEqualTo(1);
            assertThat(registry.get("judge.jobs.failed").functionCounter().count()).isEqualTo(1);
            assertThat(registry.get("judge.jobs.pending.count").gauge().value()).isZero();
            assertThat(registry.get("scheduler.backlog.oldest.seconds").gauge().value()).isZero();
        } finally {
            registry.close();
        }
    }
}
