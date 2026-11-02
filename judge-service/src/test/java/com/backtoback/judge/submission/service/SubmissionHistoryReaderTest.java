package com.backtoback.judge.submission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import com.backtoback.judge.global.config.PersistenceConfiguration;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.domain.JudgeVerdict;

/** 실제 MySQL에서 제출 상태와 최신 유효 회차의 일관성을 검증한다. */
@DataJpaTest(properties = "spring.jpa.show-sql=false")
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    {
        PersistenceConfiguration.class,
        SubmissionHistoryReader.class
    }
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class SubmissionHistoryReaderTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SubmissionHistoryReader history;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @BeforeEach
    void prepare() {
        jdbc.update("DELETE FROM judge_test_results");
        jdbc.update("DELETE FROM judge_jobs");
        jdbc.update("DELETE FROM submissions");
        jdbc.update("""
            INSERT INTO submissions(id,member_id,problem_id,problem_revision_id,context,idempotency_key,request_hash,
            language,source_code,code_size_bytes,status,judge_attempt,submitted_at,created_at,updated_at)
            VALUES (1,1,1,1,'PRACTICE','key',REPEAT('a',64),'java','x',1,'JUDGING',2,
            '2026-10-31 09:00:00','2026-10-31 09:00:00','2026-10-31 09:00:00')
            """);
        for (int attempt = 1; attempt <= 2; attempt++) {
            jdbc
                .update(
                    """
                        INSERT INTO judge_jobs(id,submission_id,judge_attempt,status,try_count,next_run_at,deadline_at,
                        finished_at,created_at,updated_at) VALUES (?,1,?,?,1,'2026-10-31 09:00:00',
                        '2026-10-31 09:10:00',?,'2026-10-31 09:00:00','2026-10-31 09:00:00')
                        """,
                    attempt,
                    attempt,
                    attempt == 1 ? "SUCCEEDED" : "RUNNING",
                    attempt == 1 ? "2026-10-31 09:01:00" : null
                );
            jdbc.update("""
                INSERT INTO judge_test_results(judge_job_id,test_asset_id,test_order,is_sample,token_status,
                created_try_count,verdict,time_ms,memory_kb,created_at,updated_at)
                VALUES (?,1,1,true,?,1,?,12,100,'2026-10-31 09:00:00','2026-10-31 09:00:00')
                """, attempt, attempt == 1 ? "DONE" : "CREATED", attempt == 1 ? "AC" : null);
        }
    }

    @Test
    @DisplayName("새 회차 진행 중에도 직전 정상 완료 결과를 최신 유효 결과로 반환한다")
    void keepsEffectiveResult() {
        var response = com.backtoback.judge.submission.dto.SubmissionResultResponse.from(history.read(1, 1));
        assertThat(response.status()).isEqualTo("JUDGING");
        assertThat(response.judgeAttempt()).isEqualTo(2);
        assertThat(response.effectiveResult().judgeAttempt()).isEqualTo(1);
        assertThat(response.effectiveResult().verdict()).isEqualTo(JudgeVerdict.AC);
        assertThat(response.effectiveResult().maxTimeMs()).isEqualTo(12);
    }

    @Test
    @DisplayName("시스템 실패와 유효 결과 없음을 오답으로 바꾸지 않는다")
    void reportsSystemFailure() {
        jdbc.update("DELETE FROM judge_test_results WHERE judge_job_id=1");
        jdbc.update("DELETE FROM judge_jobs WHERE id=1");
        jdbc.update("UPDATE submissions SET status='FAILED' WHERE id=1");
        jdbc.update("UPDATE judge_jobs SET status='FAILED',last_error='DEADLINE_EXCEEDED' WHERE id=2");
        var response = com.backtoback.judge.submission.dto.SubmissionResultResponse.from(history.read(1, 1));
        assertThat(response.effectiveResult()).isNull();
        assertThat(response.failureCode()).isEqualTo("DEADLINE_EXCEEDED");
        assertThat(response.compileMessage()).isNull();
    }

    @Test
    @DisplayName("타인 또는 미존재 제출은 결과 이력을 읽기 전에 거절한다")
    void rejectsOtherOwner() {
        assertThatThrownBy(() -> history.read(2, 1)).isInstanceOf(JudgeException.class);
        assertThatThrownBy(() -> history.read(1, 99)).isInstanceOf(JudgeException.class);
    }

    @Test
    @DisplayName("회차 목록은 과거 완료 결과를 현재 진행 회차의 결과로 대체하지 않는다")
    void preservesPastAttempt() {
        var response = com.backtoback.judge.submission.dto.SubmissionAttemptsResponse.from(history.read(1, 1));
        assertThat(response.content()).hasSize(2);
        assertThat(response.content().getFirst().judgeAttempt()).isEqualTo(2);
        assertThat(response.content().getFirst().verdict()).isNull();
        assertThat(response.content().get(1).judgeAttempt()).isEqualTo(1);
        assertThat(response.content().get(1).verdict()).isEqualTo(JudgeVerdict.AC);
    }
}
