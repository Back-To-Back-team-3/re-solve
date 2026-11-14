package com.backtoback.judge.judging.store;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** 이미 저장된 채점 작업의 V19 업그레이드를 검증한다. */
@Testcontainers(disabledWithoutDocker = true)
class SubmissionExecutionRecoveryMigrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    /** 기존 작업만 보정한 뒤 신규 입력을 위한 DB 기본값이 남지 않았는지 확인한다. */
    @Test
    @DisplayName("V19는 기존 채점 작업의 복구 횟수를 채우고 DB 기본값을 남기지 않는다")
    void backfillsExistingJobsWithoutDefault() {
        var dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(dataSource).target("18").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
            INSERT INTO submissions(id,member_id,problem_id,problem_revision_id,context,idempotency_key,request_hash,
                language,source_code,code_size_bytes,status,judge_attempt,submitted_at,created_at,updated_at)
            VALUES (1,1,1,1,'PRACTICE','key',REPEAT('a',64),'java','x',1,'QUEUED',1,NOW(),NOW(),NOW())
            """);
        jdbc.update("""
            INSERT INTO judge_jobs(id,submission_id,judge_attempt,status,try_count,
                next_run_at,deadline_at,created_at,updated_at)
            VALUES (1,1,1,'RETRY_WAITING',2,NOW(),NOW(),NOW(),NOW())
            """);

        Flyway.configure().dataSource(dataSource).target("19").load().migrate();

        assertThat(jdbc.queryForMap("SELECT status,try_count,unknown_retry_count FROM judge_jobs WHERE id=1"))
            .containsEntry("status", "RETRY_WAITING")
            .containsEntry("try_count", 2)
            .containsEntry("unknown_retry_count", 0);
        var column = jdbc.queryForMap("""
            SELECT IS_NULLABLE,COLUMN_DEFAULT FROM information_schema.columns
            WHERE table_schema=DATABASE() AND table_name='judge_jobs' AND column_name='unknown_retry_count'
            """);
        assertThat(column).containsEntry("IS_NULLABLE", "NO").containsEntry("COLUMN_DEFAULT", null);
    }
}
