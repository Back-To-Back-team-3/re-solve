package com.backtoback.problem.admin.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.event.store.JdbcOutboxStore;
import com.backtoback.problem.admin.service.AdminOutboxEventService;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;

/** 실제 MySQL 행 잠금으로 재처리·Relay 경합과 감사 원자성을 확인한다. */
@SpringJUnitConfig(AdminOutboxEventStoreTest.Beans.class)
@Testcontainers(disabledWithoutDocker = true)
class AdminOutboxEventStoreTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 12, 9, 0, 0, 123456000);
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withUsername("root");
    @Autowired
    private AdminOutboxEventService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private JdbcOutboxStore relayStore;

    /** 운영 마이그레이션의 테이블 정의와 실패 이벤트를 준비한다. */
    @BeforeEach
    void prepare() {
        jdbc.execute("DROP TABLE IF EXISTS audit_logs");
        jdbc.execute("DROP TABLE IF EXISTS outbox_events");
        new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V17__common_create_audit_logs.sql"),
            new ClassPathResource("db/migration/V20__common_create_outbox_events.sql")
        ).execute(jdbc.getDataSource());
        jdbc
            .update(
                """
                    INSERT INTO outbox_events(id,event_id,event_type,schema_version,aggregate_type,aggregate_id,
                        correlation_id,payload,status,retry_count,occurred_at,published_at,created_at,updated_at)
                    VALUES (1,?,'ProblemValidated','v1','PROBLEM','1','request-1',JSON_OBJECT('value',1),
                        'FAILED',6,?,?,?,?)
                    """,
                "11111111-1111-4111-8111-111111111111",
                NOW.minusDays(7),
                NOW.minusDays(1),
                NOW.minusDays(7),
                NOW.minusDays(1)
            );
    }

    /**
     * 두 운영 요청을 실제 행 잠금 뒤에 대기시켜 한 번의 전환·감사만 허용한다.
     *
     * @throws Exception DB 연결 또는 동시 요청을 제한시간에 완료하지 못한 경우
     */
    @Test
    @DisplayName("동일 실패 이벤트의 동시 재처리는 한 건만 성공하고 다른 요청은 409이며 감사도 한 건이다")
    void serializesConcurrentRequests() throws Exception {
        var acquired = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(3)) {
            var blocker = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                assertThat(jdbc.queryForObject("SELECT id FROM outbox_events WHERE id=1 FOR UPDATE", Long.class))
                    .isEqualTo(1L);
                acquired.countDown();
                awaitRelease(release);
                return true;
            }));
            assertThat(acquired.await(10, TimeUnit.SECONDS)).isTrue();
            var first = executor.submit(this::reprocessStatus);
            var second = executor.submit(this::reprocessStatus);
            try {
                awaitWaiters(2);
            } finally {
                release.countDown();
            }
            assertThat(blocker.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(202, 409);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs", Integer.class)).isOne();
        assertThat(jdbc.queryForMap("SELECT status,retry_count,published_at,updated_at FROM outbox_events"))
            .containsEntry("status", "PENDING")
            .containsEntry("retry_count", 0)
            .containsEntry("published_at", null);
        assertThat(jdbc.queryForObject("SELECT updated_at FROM outbox_events", LocalDateTime.class)).isEqualTo(NOW);
        var audit = jdbc.queryForMap("""
            SELECT actor_id,client_ip,reason,created_at,
                before_state->>'$.status' AS old_status,before_state->>'$.retryCount' AS old_retries,
                before_state->>'$.publishedAt' AS old_published,
                after_state->>'$.status' AS new_status,after_state->>'$.retryCount' AS new_retries,
                after_state->>'$.publishedAt' AS new_published FROM audit_logs
            """);
        assertThat(audit)
            .containsEntry("actor_id", 7L)
            .containsEntry("client_ip", "192.0.2.10")
            .containsEntry("reason", "발행 복구")
            .containsEntry("old_status", "FAILED")
            .containsEntry("old_retries", "6")
            .containsEntry("new_status", "PENDING")
            .containsEntry("new_retries", "0")
            .containsEntry("new_published", "null");
        assertThat(audit.get("old_published")).isEqualTo("2026-11-11 09:00:00.123456");
        assertThat(jdbc.queryForObject("SELECT created_at FROM audit_logs", LocalDateTime.class)).isEqualTo(NOW);
        assertThat(
            jdbc
                .queryForMap(
                    "SELECT event_id,correlation_id,occurred_at,payload->>'$.value' AS value FROM outbox_events"
                )
        )
            .containsEntry("event_id", "11111111-1111-4111-8111-111111111111")
            .containsEntry("correlation_id", "request-1")
            .containsEntry("value", "1");
        assertThat(jdbc.queryForObject("SELECT occurred_at FROM outbox_events", LocalDateTime.class))
            .isEqualTo(NOW.minusDays(7));
    }

    /** 감사 저장 실패가 이벤트 상태·재시도 횟수·발행 시각도 롤백하는지 확인한다. */
    @Test
    @DisplayName("감사 INSERT가 실패하면 Outbox 전환 전체를 롤백한다")
    void rollsBackAuditFailure() {
        jdbc.execute("""
            CREATE TRIGGER reject_outbox_audit BEFORE INSERT ON audit_logs
            FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='rejected'
            """);
        assertThatThrownBy(() -> service.reprocess(1, 7, "발행 복구", "192.0.2.10"))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForMap("SELECT status,retry_count FROM outbox_events"))
            .containsEntry("status", "FAILED")
            .containsEntry("retry_count", 6);
        assertThat(jdbc.queryForObject("SELECT published_at FROM outbox_events", LocalDateTime.class))
            .isEqualTo(NOW.minusDays(1));
        assertThat(jdbc.queryForObject("SELECT updated_at FROM outbox_events", LocalDateTime.class))
            .isEqualTo(NOW.minusDays(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs", Integer.class)).isZero();
    }

    /**
     * 실제 공통 Relay 저장소가 잠근 행을 운영 요청이 덮어쓰지 않는지 확인한다.
     *
     * @throws Exception Relay 또는 운영 요청을 제한시간에 완료하지 못한 경우
     */
    @Test
    @DisplayName("재발행 중인 Relay 잠금을 기다린 운영 요청은 발행 완료 상태를 보고 409를 반환한다")
    void preservesRelayPublication() throws Exception {
        service.reprocess(1, 7, "발행 복구", "192.0.2.10");
        var acquired = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var relay = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                assertThat(relayStore.lockNextPending(0)).isPresent();
                acquired.countDown();
                awaitRelease(release);
                relayStore.markPublished(1, NOW.plusSeconds(1));
                return true;
            }));
            assertThat(acquired.await(10, TimeUnit.SECONDS)).isTrue();
            var request = executor.submit(this::reprocessStatus);
            try {
                awaitWaiters(1);
            } finally {
                release.countDown();
            }
            assertThat(relay.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(request.get(10, TimeUnit.SECONDS)).isEqualTo(409);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_events", String.class)).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT published_at FROM outbox_events", LocalDateTime.class))
            .isEqualTo(NOW.plusSeconds(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs", Integer.class)).isOne();
    }

    /**
     * 실제 저장소·트랜잭션의 성공과 상태 충돌 결과를 수집한다.
     *
     * @return 성공이면 202, 상태 충돌이면 409
     */
    private int reprocessStatus() {
        try {
            service.reprocess(1, 7, "발행 복구", "192.0.2.10");
            return 202;
        } catch (ProblemException failure) {
            assertThat(failure.getErrorCode()).isEqualTo(ProblemErrorCode.OUTBOX_EVENT_NOT_REPROCESSABLE);
            return 409;
        }
    }

    /**
     * 실제 MySQL 잠금 대기 수를 확인하여 경합이 발생한 뒤에만 잠금을 해제한다.
     *
     * @param expected 필요한 대기 트랜잭션 수
     * @throws InterruptedException 대기 중 인터럽트된 경우
     */
    private void awaitWaiters(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        long waiting = 0;
        while (System.nanoTime() < deadline) {
            waiting = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT REQUESTING_ENGINE_TRANSACTION_ID)
                FROM performance_schema.data_lock_waits
                """, Long.class);
            if (waiting >= expected) {
                return;
            }
            Thread.sleep(20);
        }
        assertThat(waiting).isGreaterThanOrEqualTo(expected);
    }

    /**
     * 경합이 확인될 때까지 잠금을 보존하고 실패한 테스트도 제한시간 안에 정리한다.
     *
     * @param release 잠금 해제 신호
     * @throws IllegalStateException 대기 스레드가 인터럽트된 경우
     */
    private void awaitRelease(CountDownLatch release) {
        try {
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(
        {
            AdminOutboxEventService.class,
            AdminOutboxEventStore.class
        }
    )
    static class Beans {
        /**
         * 독립 MySQL 연결 공급자를 제공한다.
         *
         * @return 테스트 DB 연결 공급자
         */
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        }

        /**
         * 실제 SQL 실행기를 제공한다.
         *
         * @param dataSource 테스트 DB 연결 공급자
         * @return JDBC 실행기
         */
        @Bean
        JdbcTemplate jdbc(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        /**
         * JDBC 원자 저장용 트랜잭션 관리자를 제공한다.
         *
         * @param dataSource 테스트 DB 연결 공급자
         * @return 트랜잭션 관리자
         */
        @Bean
        PlatformTransactionManager transactions(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        /**
         * 마이크로초가 있는 고정 기준 시각을 제공한다.
         *
         * @return 서울 오전 9시를 가리키는 시계
         */
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-11-12T00:00:00.123456Z"), ZoneOffset.UTC);
        }

        /**
         * 운영과 같은 공통 Relay 행 잠금을 제공한다.
         *
         * @param jdbc 테스트 SQL 실행기
         * @return 공통 Outbox 저장소
         */
        @Bean
        JdbcOutboxStore relayStore(JdbcTemplate jdbc) {
            return new JdbcOutboxStore(jdbc);
        }
    }
}
