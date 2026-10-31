package com.backtoback.judge.judging.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** 이미 적용된 V14 이력을 보존하는 후속 마이그레이션을 검증한다. */
@Testcontainers(disabledWithoutDocker = true)
class ValidationRunMigrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    /** 기존 성공·오류 이력을 보존하면서 대표 판정만 제거한다. */
    @Test
    @DisplayName("V15에서 업그레이드해도 실행 이력과 생성기 필수 제약을 유지한다")
    void preservesExistingRuns() {
        Flyway
            .configure()
            .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
            .target("15")
            .load()
            .migrate();
        var jdbc
            = new JdbcTemplate(
                new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
            );
        jdbc.update("""
            INSERT INTO judge_validation_jobs
            (validation_id,problem_revision_id,language,request_hash,correlation_id,status,try_count,
            next_run_at,deadline_at,created_at,updated_at)
            VALUES (2001,1001,'java',?,'trace','RUNNING',2,NOW(),NOW(),NOW(),NOW())
            """, "a".repeat(64));
        jdbc.update("""
            INSERT INTO judge_validation_runs
            (validation_id,try_count,lease_token,runner_version,result,verdict,max_time_ms,error_message,
            started_at,finished_at,created_at) VALUES
            (2001,1,'first',NULL,'SYSTEM_ERROR',NULL,10,'정제한 오류',NOW(),NOW(),NOW()),
            (2001,2,'second','java-runner-v1','SUCCEEDED','WA',20,NULL,NOW(),NOW(),NOW())
            """);
        var before = jdbc.queryForList("SELECT * FROM judge_validation_runs ORDER BY try_count");
        before.forEach(row -> row.remove("verdict"));
        var flyway = Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load();
        flyway.migrate();
        flyway.validate();
        assertThat(jdbc.queryForList("SELECT * FROM judge_validation_runs ORDER BY try_count")).isEqualTo(before);
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO judge_validation_runs
            (validation_id,try_count,lease_token,result,started_at,finished_at,created_at)
            VALUES (2001,3,'third','SUCCEEDED',NOW(),NOW(),NOW())
            """)).isInstanceOf(DataAccessException.class);
        jdbc.update("""
            INSERT INTO judge_validation_runs
            (validation_id,try_count,lease_token,runner_version,result,started_at,finished_at,created_at)
            VALUES (2001,3,'third','java-runner-v1','SUCCEEDED',NOW(),NOW(),NOW())
            """);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM judge_validation_runs", Integer.class)).isEqualTo(3);
    }
}
