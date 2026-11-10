package com.backtoback.judge.judging.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
            INSERT INTO judge_jobs(id,submission_id,judge_attempt,status,try_count,unknown_retry_count,
                next_run_at,deadline_at,created_at,updated_at)
            VALUES (1,1,1,'PENDING',1,0,'2026-10-31 09:00:00','2026-10-31 09:10:00',
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
        assertThat(store.saveResult(lease, 1, "token", result)).isTrue();
        assertThat(jdbc.queryForObject("SELECT actual_output FROM judge_test_results", String.class)).isNull();
        given(clock.instant()).willReturn(START.plusSeconds(120));
        assertThat(store.heartbeat(lease)).isFalse();
        assertThat(store.saveResult(lease, 1, "token", result)).isFalse();
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
                "token",
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
        assertThat(store.complete(lease, data)).isTrue();
        assertThat(store.complete(lease, data)).isFalse();
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
        assertThat(store.recoverOne()).isTrue();
        assertThat(store.recoverOne()).isFalse();
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

    /** 확정 거부는 원자적으로 재시도하고 이전 임대의 거부 응답은 현재 요청을 변경하지 않는지 확인한다. */
    @Test
    @DisplayName("확정 생성 거부는 백오프 재시도로 전환하고 늦은 거부는 무시한다")
    void retriesRejectedCreate() {
        var first = store.claim().orElseThrow();
        assertThat(store.beginCreate(first, 1)).isTrue();
        assertThat(store.rejectCreate(first, 1)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM judge_jobs", String.class)).isEqualTo("RETRY_WAITING");
        assertThat(jdbc.queryForObject("SELECT token_status FROM judge_test_results", String.class))
            .isEqualTo("NOT_CREATED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM judge_job_runs", Integer.class)).isEqualTo(1);
        given(clock.instant()).willReturn(START.plusSeconds(10));
        var second = store.claim().orElseThrow();
        assertThat(second.tryCount()).isEqualTo(2);
        assertThat(store.prepare(second)).isTrue();
        assertThat(store.beginCreate(second, 1)).isTrue();
        assertThat(store.rejectCreate(first, 1)).isFalse();
        assertThat(store.saveToken(second, 1, "token")).isTrue();
        assertThat(store.rejectCreate(second, 1)).isFalse();
        assertThat(jdbc.queryForObject("SELECT token_status FROM judge_test_results", String.class))
            .isEqualTo("CREATED");
    }

    /** 회수 전 토큰과 늦은 실행기의 쓰기를 함께 검증한다. */
    @Test
    @DisplayName("임대 회수 후 기존 토큰과 64KiB를 넘는 컴파일 메시지를 보존한다")
    void recoversLease() {
        jdbc.update("""
            INSERT INTO judge_test_results(judge_job_id,test_asset_id,test_order,is_sample,token_status,
                created_at,updated_at)
            SELECT judge_job_id,2,2,false,'NOT_CREATED',created_at,updated_at FROM judge_test_results
            """);
        var first = store.claim().orElseThrow();
        assertThat(store.beginCreate(first, 1)).isTrue();
        assertThat(store.saveToken(first, 1, "completed-token")).isTrue();
        String diagnostic = "오류".repeat(12_000);
        var compileError = new JudgeTestResult(State.COMPLETED, JudgeVerdict.CE, null, null, null, null, diagnostic);
        assertThat(store.saveResult(first, 1, "completed-token", compileError)).isTrue();
        assertThat(store.beginCreate(first, 2)).isTrue();
        assertThat(store.saveToken(first, 2, "existing-token")).isTrue();
        given(clock.instant()).willReturn(START.plusSeconds(119));
        assertThat(store.recoverOne()).isFalse();

        given(clock.instant()).willReturn(START.plusSeconds(120));
        assertThat(store.recoverOne()).isTrue();
        assertThat(store.recoverOne()).isFalse();
        assertThat(jdbc.queryForObject("SELECT result FROM judge_job_runs", String.class)).isEqualTo("LEASE_EXPIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM submissions", String.class)).isEqualTo("QUEUED");
        assertThat(store.heartbeat(first)).isFalse();

        var second = store.claim().orElseThrow();
        assertThat(second.tryCount()).isEqualTo(2);
        assertThat(second.token()).isNotEqualTo(first.token());
        assertThat(store.prepare(second)).isTrue();
        assertThat(store.completedOrders(second)).containsExactly(1);
        assertThat(store.resumableTokens(second)).isEqualTo(Map.of(2, "existing-token"));
        assertThat(store.beginCreate(second, 1)).isFalse();
        var result = new JudgeTestResult(State.COMPLETED, JudgeVerdict.AC, 1, BigDecimal.ONE, null, null, null);
        assertThat(store.beginCreate(second, 2)).isFalse();
        assertThat(store.saveResult(first, 2, "existing-token", result)).isFalse();
        assertThat(store.saveResult(second, 2, "different-token", result)).isFalse();
        assertThat(store.saveResult(second, 2, "existing-token", result)).isTrue();
        assertThat(jdbc.queryForList("""
            SELECT created_try_count FROM judge_test_results ORDER BY test_order
            """, Integer.class)).containsExactly(1, 1);
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
        assertThat(store.complete(second, data)).isTrue();
        assertThat(jdbc.queryForObject("SELECT compile_message FROM submissions", String.class)).isEqualTo(diagnostic);
    }

    /** 생성 여부가 불명확한 테스트의 재생성 대기와 마지막 임대 회수의 종결을 확인한다. */
    @Test
    @DisplayName("UNKNOWN은 유예 후 다음 시도에서 재생성하고 시도 상한에서 실패 Outbox를 한 번 남긴다")
    void waitsBeforeRetryingUnknownAndExhaustsLease() {
        var first = store.claim().orElseThrow();
        given(clock.instant()).willReturn(START.plusSeconds(119));
        assertThat(store.beginCreate(first, 1)).isTrue();
        given(clock.instant()).willReturn(START.plusSeconds(120));
        assertThat(store.recoverOne()).isTrue();
        assertThat(store.claim()).isEmpty();

        given(clock.instant()).willReturn(START.plusSeconds(149));
        var second = store.claim().orElseThrow();
        assertThat(store.prepare(second)).isTrue();
        assertThat(store.resumableTokens(second)).isEmpty();
        assertThat(store.beginCreate(second, 1)).isTrue();
        given(clock.instant()).willReturn(START.plusSeconds(269));
        assertThat(store.recoverOne()).isTrue();
        var third = store.claim().orElseThrow();
        assertThat(third.tryCount()).isEqualTo(3);
        assertThat(store.prepare(third)).isTrue();
        assertThat(store.beginCreate(third, 1)).isTrue();

        given(clock.instant()).willReturn(START.plusSeconds(389));
        assertThat(store.recoverOne()).isTrue();
        assertThat(store.recoverOne()).isFalse();
        assertThat(store.recoverUnknownOne()).isTrue();
        assertThat(jdbc.queryForObject("SELECT token_status FROM judge_test_results", String.class))
            .isEqualTo("NOT_CREATED");
        assertThat(jdbc.queryForObject("SELECT status FROM submissions", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT last_error FROM judge_jobs", String.class)).isEqualTo("LEASE_EXHAUSTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM judge_job_runs", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(1);
    }

    /** 이전 시도의 응답 유실이 새 시도의 임대나 재생성 횟수에 영향을 주지 않는지 확인한다. */
    @Test
    @DisplayName("새 시도를 선점하고 준비하기 전에는 이전 시도의 UNKNOWN으로 임대를 회수하지 않는다")
    void keepsNewLeaseWhenPreviousAttemptIsUnknown() {
        var first = store.claim().orElseThrow();
        given(clock.instant()).willReturn(START.plusSeconds(119));
        assertThat(store.beginCreate(first, 1)).isTrue();
        given(clock.instant()).willReturn(START.plusSeconds(120));
        assertThat(store.recoverOne()).isTrue();

        given(clock.instant()).willReturn(START.plusSeconds(149));
        var second = store.claim().orElseThrow();
        assertThat(second.tryCount()).isEqualTo(2);
        assertThat(store.recoverUnknownOne()).isFalse();
        assertThat(jdbc.queryForObject("SELECT lease_token FROM judge_jobs WHERE id=1", String.class))
            .isEqualTo(second.token());
        assertThat(jdbc.queryForObject("SELECT unknown_retry_count FROM judge_jobs WHERE id=1", Integer.class))
            .isZero();
        assertThat(store.prepare(second)).isTrue();
        assertThat(store.beginCreate(second, 1)).isTrue();
    }

    /** 별도 UNKNOWN 복구가 임대를 교체하며 동일 시도에서 재생성 두 번을 제한하는지 확인한다. */
    @Test
    @DisplayName("UNKNOWN은 30초 후 같은 시도에서 두 번 재생성하고 세 번째 유실은 시스템 실패로 닫는다")
    void recoversUnknownBeforeLeaseExpires() {
        var first = store.claim().orElseThrow();
        assertThat(store.beginCreate(first, 1)).isTrue();
        given(clock.instant()).willReturn(START.plusSeconds(29));
        assertThat(store.recoverUnknownOne()).isFalse();
        given(clock.instant()).willReturn(START.plusSeconds(30));
        assertThat(store.recoverOne()).isFalse();
        assertThat(store.recoverUnknownOne()).isTrue();
        assertThat(store.recoverUnknownOne()).isFalse();

        var second = store.claim().orElseThrow();
        assertThat(second.tryCount()).isEqualTo(first.tryCount());
        assertThat(second.token()).isNotEqualTo(first.token());
        assertThat(store.prepare(second)).isTrue();
        assertThat(store.beginCreate(second, 1)).isTrue();
        assertThat(store.saveToken(first, 1, "late-token")).isFalse();
        given(clock.instant()).willReturn(START.plusSeconds(60));
        assertThat(store.recoverUnknownOne()).isTrue();

        var third = store.claim().orElseThrow();
        assertThat(third.tryCount()).isEqualTo(first.tryCount());
        assertThat(store.prepare(third)).isTrue();
        assertThat(store.beginCreate(third, 1)).isTrue();
        given(clock.instant()).willReturn(START.plusSeconds(90));
        assertThat(store.recoverUnknownOne()).isTrue();
        assertThat(store.recoverUnknownOne()).isFalse();
        assertThat(jdbc.queryForObject("SELECT error_message FROM judge_job_runs", String.class))
            .isEqualTo("JUDGE0_TIMEOUT");
        assertThat(jdbc.queryForObject("SELECT try_count FROM judge_jobs", Integer.class)).isEqualTo(2);

        given(clock.instant()).willReturn(START.plusSeconds(100));
        var nextAttempt = store.claim().orElseThrow();
        assertThat(store.prepare(nextAttempt)).isTrue();
        assertThat(jdbc.queryForObject("SELECT unknown_retry_count FROM judge_jobs", Integer.class)).isZero();
    }

    /** 재선점 없이 기한이 끝나도 이미 시작한 동일 시도의 이력이 한 건 남는지 확인한다. */
    @Test
    @DisplayName("UNKNOWN 복구 대기 중 기한이 끝나면 직전 임대의 실행 이력과 실패 이벤트를 한 번 남긴다")
    void closesStartedAttemptWhenRecoveryWaitReachesDeadline() {
        given(clock.instant()).willReturn(START.plusSeconds(550));
        var lease = store.claim().orElseThrow();
        assertThat(store.beginCreate(lease, 1)).isTrue();

        given(clock.instant()).willReturn(START.plusSeconds(580));
        assertThat(store.recoverUnknownOne()).isTrue();
        assertThat(store.heartbeat(lease)).isFalse();
        assertThat(store.saveToken(lease, 1, "late-token")).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM judge_jobs", String.class)).isEqualTo("RETRY_WAITING");

        given(clock.instant()).willReturn(START.plusSeconds(600));
        assertThat(store.claim()).isEmpty();
        assertThat(store.recoverOne()).isTrue();
        assertThat(store.recoverOne()).isFalse();
        assertThat(jdbc.queryForMap("SELECT try_count,lease_token,result,error_message FROM judge_job_runs"))
            .containsEntry("try_count", 1)
            .containsEntry("lease_token", lease.token())
            .containsEntry("result", "SYSTEM_ERROR")
            .containsEntry("error_message", "DEADLINE_EXCEEDED");
        assertThat(jdbc.queryForObject("SELECT lease_token FROM judge_jobs", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM submissions", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(1);
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
