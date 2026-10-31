package com.backtoback.judge.judging.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
import com.backtoback.judge.judging.domain.JudgeVerdict;
import com.backtoback.judge.judging.service.execution.dto.JudgeExecutionResultResponse.State;
import com.backtoback.judge.judging.service.execution.dto.JudgeTestResult;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 실제 MySQL의 선점·임대와 종결/Outbox 원자성을 검증한다. */
@DataJpaTest(properties = "spring.jpa.show-sql=false")
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    {
        PersistenceConfiguration.class,
        OutboxStorageConfiguration.class,
        SubmissionExecutionStore.class,
        SubmissionExecutionStoreTest.Beans.class
    }
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class SubmissionExecutionStoreTest {
    private static final Instant START = Instant.parse("2026-10-31T00:00:00Z");
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SubmissionExecutionStore store;
    @Autowired
    private Clock clock;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @BeforeEach
    void prepare() {
        given(clock.instant()).willReturn(START);
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM judge_test_results");
        jdbc.update("DELETE FROM judge_job_runs");
        jdbc.update("DELETE FROM judge_jobs");
        jdbc.update("DELETE FROM submissions");
        jdbc.update("""
            INSERT INTO submissions(id,member_id,problem_id,problem_revision_id,context,idempotency_key,request_hash,
            language,source_code,code_size_bytes,status,judge_attempt,submitted_at,created_at,updated_at)
            VALUES (1,1,1,1,'PRACTICE','key',REPEAT('a',64),'java','x',1,'QUEUED',1,
            '2026-10-31 09:00:00','2026-10-31 09:00:00','2026-10-31 09:00:00')
            """);
        jdbc.update("""
            INSERT INTO judge_jobs(id,submission_id,judge_attempt,status,try_count,next_run_at,deadline_at,
            created_at,updated_at) VALUES (1,1,1,'PENDING',1,'2026-10-31 09:00:00','2026-10-31 09:10:00',
            '2026-10-31 09:00:00','2026-10-31 09:00:00')
            """);
        jdbc.update("""
            INSERT INTO judge_test_results(judge_job_id,test_asset_id,test_order,is_sample,token_status,
            created_at,updated_at) VALUES (1,1,1,false,'NOT_CREATED','2026-10-31 09:00:00','2026-10-31 09:00:00')
            """);
    }

    @Test
    @DisplayName("경쟁 실행기는 같은 작업을 한 번만 선점한다")
    void claimsOnce() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                barrier.await();
                return store.claim();
            });
            var second = pool.submit(() -> {
                barrier.await();
                return store.claim();
            });
            int claimed
                = (first.get(10, TimeUnit.SECONDS).isPresent() ? 1 : 0)
                    + (second.get(10, TimeUnit.SECONDS).isPresent() ? 1 : 0);
            assertThat(claimed).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("숨김 출력은 저장하지 않고 만료한 임대의 결과를 거절한다")
    void protectsResults() {
        var lease = store.claim().orElseThrow();
        assertThat(store.prepare(lease)).isTrue();
        assertThat(store.beginCreate(lease, 1)).isTrue();
        assertThat(store.saveToken(lease, 1, "token")).isTrue();
        var result = new JudgeTestResult(State.COMPLETED, JudgeVerdict.AC, 1, BigDecimal.ONE, "secret", null, null);
        assertThat(store.saveResult(lease, 1, result)).isTrue();
        assertThat(jdbc.queryForObject("SELECT actual_output FROM judge_test_results", String.class)).isNull();
        given(clock.instant()).willReturn(START.plusSeconds(120));
        assertThat(store.heartbeat(lease)).isFalse();
        assertThat(store.saveResult(lease, 1, result)).isFalse();
    }

    @Test
    @DisplayName("현재 회차가 바뀌면 이전 실행 토큰과 결과를 반영하지 않는다")
    void rejectsOldAttempt() {
        var lease = store.claim().orElseThrow();
        assertThat(store.beginCreate(lease, 1)).isTrue();
        jdbc.update("UPDATE submissions SET judge_attempt=2 WHERE id=1");
        assertThat(store.saveToken(lease, 1, "late-token")).isFalse();
        assertThat(store.active(lease)).isFalse();
    }

    @Test
    @DisplayName("종결 결과와 Outbox는 한 번만 저장하고 숨김 결과도 대표 판정에 포함한다")
    void completesOnce() {
        var lease = store.claim().orElseThrow();
        store.beginCreate(lease, 1);
        store.saveToken(lease, 1, "token");
        store
            .saveResult(
                lease,
                1,
                new JudgeTestResult(State.COMPLETED, JudgeVerdict.WA, 12, BigDecimal.TEN, "private", null, null)
            );
        var data
            = new com.backtoback.judge.judging.dto.SubmissionJudgingDataResponse(
                "1",
                "1",
                "제목",
                1,
                List.of("구현"),
                List.of("3"),
                null,
                null,
                List.of()
            );
        assertThat(store.complete(lease, data, null)).isTrue();
        assertThat(store.complete(lease, data, null)).isFalse();
        assertThat(jdbc.queryForObject("SELECT verdict FROM submissions", String.class)).isEqualTo("WA");
        assertThat(jdbc.queryForObject("SELECT status FROM judge_jobs", String.class)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM judge_job_runs", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT correlation_id FROM outbox_events", String.class))
            .isEqualTo(lease.correlationId())
            .matches("sched-JudgeJobExecutor-[0-9a-f-]{36}");
    }

    @Test
    @DisplayName("대기 상태의 기한 초과는 실행 시도를 만들지 않고 실패 이벤트와 함께 종결한다")
    void expiresWithoutRun() {
        given(clock.instant()).willReturn(START.plusSeconds(600));
        assertThat(store.claim()).isEmpty();
        assertThat(store.expireOne()).isTrue();
        assertThat(store.expireOne()).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM submissions", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM judge_job_runs", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT correlation_id FROM outbox_events", String.class))
            .matches("sched-JudgeJobExpiry-[0-9a-f-]{36}");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("확정 시스템 실패는 백오프 뒤 다음 시도로 선점하고 불명확한 생성은 재시도하지 않는다")
    void retriesOnlyKnownFailure() {
        var first = store.claim().orElseThrow();
        assertThat(store.fail(first, "TEST_DATA_UNAVAILABLE")).isTrue();
        assertThat(store.claim()).isEmpty();
        given(clock.instant()).willReturn(START.plusSeconds(10));
        var second = store.claim().orElseThrow();
        assertThat(second.tryCount()).isEqualTo(2);
        assertThat(store.beginCreate(second, 1)).isTrue();
        assertThat(store.fail(second, "JUDGE0_UNAVAILABLE")).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM judge_jobs", String.class)).isEqualTo("RUNNING");
    }

    @TestConfiguration
    static class Beans {
        @Bean
        @Primary
        Clock testClock() {
            return mock(Clock.class);
        }

        @Bean
        ObjectMapper mapper() {
            return JsonMapper.builder().build();
        }
    }
}
