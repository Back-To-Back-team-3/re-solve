package com.backtoback.judge.judging.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** 실제 DB에서 현재 시도의 결과 노출과 고정 테스트 순서를 검증한다. */
@Testcontainers(disabledWithoutDocker = true)
class ValidationProgressServiceTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    private static JdbcTemplate jdbc;
    private ValidationProgressService service;

    /** 최신 스키마를 구성한다. */
    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load().migrate();
        jdbc
            = new JdbcTemplate(
                new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
            );
    }

    /** 이전 시도 결과와 미실행 테스트가 섞인 재시도 작업을 준비한다. */
    @BeforeEach
    void prepare() {
        jdbc.update("DELETE FROM judge_validation_test_results");
        jdbc.update("DELETE FROM judge_validation_runs");
        jdbc.update("DELETE FROM judge_validation_jobs");
        jdbc.update("""
            INSERT INTO judge_validation_jobs
            (validation_id,problem_revision_id,language,request_hash,correlation_id,status,try_count,next_run_at,
            deadline_at,created_at,updated_at)
            VALUES (2001,1001,'java',?,'trace','RUNNING',2,NOW(),DATE_ADD(NOW(),INTERVAL 10 MINUTE),NOW(),NOW())
            """, "a".repeat(64));
        jdbc.update("""
            INSERT INTO judge_validation_test_results
            (validation_id,test_asset_id,test_order,token_status,created_try_count,verdict,time_ms,memory_kb,
            created_at,updated_at) VALUES
            (2001,503,3,'NOT_CREATED',NULL,NULL,NULL,NULL,NOW(),NOW()),
            (2001,502,2,'DONE',1,'AC',90,100,NOW(),NOW()),
            (2001,501,1,'DONE',2,'WA',2,128,NOW(),NOW())
            """);
        service = new ValidationProgressService(jdbc);
    }

    /** 이전 시도 결과를 가리고 고정 순서로 현재 결과를 노출한다. */
    @Test
    @DisplayName("RUNNING 조회는 현재 시도의 결과만 고정 테스트 순서로 반환한다")
    void returnsCurrentAttemptOnly() {
        var response = service.find(2001);
        assertThat(response.status()).isEqualTo("RUNNING");
        assertThat(response.tests()).extracting(test -> test.testOrder()).containsExactly(1, 2, 3);
        assertThat(response.tests()).extracting(test -> test.verdict()).containsExactly("WA", null, null);
        assertThat(response.tests()).extracting(test -> test.timeMs()).containsExactly(2, null, null);
        assertThat(response.tests()).extracting(test -> test.memoryKb()).containsExactly(128, null, null);
        assertThat(response.maxTimeMs()).isNull();
    }

    /** 재시도 선점 전에는 직전 실행의 실패가 현재 결과처럼 보이지 않는다. */
    @Test
    @DisplayName("QUEUED와 RETRY_WAITING은 테스트 자리를 유지하고 모든 판정·측정값을 가린다")
    void masksWaitingResults() {
        for (String status : new String[] {
            "QUEUED",
            "RETRY_WAITING"
        }) {
            jdbc.update("UPDATE judge_validation_jobs SET status=?", status);
            assertThat(service.find(2001).tests()).hasSize(3).allSatisfy(test -> {
                assertThat(test.verdict()).isNull();
                assertThat(test.timeMs()).isNull();
                assertThat(test.memoryKb()).isNull();
            });
        }
    }

    /** 시스템 실패로 중단된 시도의 관측값을 보존하고 미실행 판정을 합성하지 않는다. */
    @Test
    @DisplayName("ERROR 조회는 현재 시도의 부분 결과와 마지막 시도 최대 시간을 반환한다")
    void preservesPartialErrorResults() {
        jdbc.update("UPDATE judge_validation_jobs SET status='ERROR',finished_at=NOW(),last_error='정제한 오류'");
        jdbc.update("""
            INSERT INTO judge_validation_runs
            (validation_id,try_count,lease_token,result,max_time_ms,started_at,finished_at,created_at)
            VALUES (2001,1,'old','SYSTEM_ERROR',90,NOW(),NOW(),NOW()),
            (2001,2,'current','SYSTEM_ERROR',2,NOW(),NOW(),NOW())
            """);
        var response = service.find(2001);
        assertThat(response.status()).isEqualTo("ERROR");
        assertThat(response.tests()).extracting(test -> test.verdict()).containsExactly("WA", null, null);
        assertThat(response.maxTimeMs()).isEqualTo(2);
        assertThat(response.message()).isEqualTo("정제한 오류");
        assertThat(response.runtimeKey()).isNull();
        assertThat(response.runnerVersion()).isNull();
    }

    /** 관측한 시스템 오류를 코드 오답으로 바꾸지 않는다. */
    @Test
    @DisplayName("관측한 SYSTEM_ERROR 판정은 해당 테스트에 그대로 반환한다")
    void returnsObservedSystemError() {
        jdbc.update("UPDATE judge_validation_test_results SET verdict='SYSTEM_ERROR' WHERE test_order=1");
        assertThat(service.find(2001).tests().getFirst().verdict()).isEqualTo("SYSTEM_ERROR");
    }

    /** 테스트 자료 조회 전에는 빈 배열을 제공한다. */
    @Test
    @DisplayName("테스트 목록을 아직 모르는 작업은 빈 배열을 반환한다")
    void returnsEmptyTests() {
        jdbc.update("DELETE FROM judge_validation_test_results");
        jdbc.update("UPDATE judge_validation_jobs SET status='QUEUED',try_count=0");
        assertThat(service.find(2001).tests()).isEmpty();
    }
}
