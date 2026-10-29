package com.backtoback.common.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.event.store.JdbcEventCleanupStore;
import com.backtoback.common.scheduling.config.EventCleanupStorageConfiguration;

/**
 * 실제 MySQL에서 보관기간·배치 상한·독립 커밋과 실패 후 재기동 경계를 검증한다.
 * 예약 실행 없이 저장 설정과 실행기를 직접 연결해 삭제 결과를 별도 연결에서 확인한다.
 */
@Testcontainers(disabledWithoutDocker = true)
class EventRecordCleanupIntegrationTest {
    @Container
    private static final MySQLContainer DATABASE
        = new MySQLContainer("mysql:8.4").withUrlParam("connectTimeout", "5000").withUrlParam("socketTimeout", "10000");

    private static final LocalDateTime NOW = LocalDateTime.parse("2026-10-30T09:00:00.123456");

    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private AnnotationConfigApplicationContext context;
    private TransactionTemplate transaction;
    private JdbcEventCleanupStore store;
    private EventRecordCleanupService service;

    /**
     * 테이블 생성은 서비스 역할의 테스트 준비에서 수행하고 공통 설정은 기존 스키마에 연결한다.
     */
    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP TABLE IF EXISTS cleanup_failure_probe");
        jdbcTemplate.execute("DROP TABLE IF EXISTS outbox_events");
        jdbcTemplate.execute("DROP TABLE IF EXISTS processed_events");
        jdbcTemplate.execute("DROP TABLE IF EXISTS audit_logs");
        new ResourceDatabasePopulator(new ClassPathResource("db/event-cleanup-test-schema.sql")).execute(dataSource);
        openContext();
    }

    /**
     * 테스트마다 생성한 Spring 설정을 종료한다.
     */
    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    /**
     * 생성 시각이 오래됐어도 발행 상태와 완료 시각 조건을 모두 만족한 행만 삭제하는지 확인한다.
     */
    @Test
    @DisplayName("Outbox는 7일보다 오래된 발행 완료만 삭제하고 경계·최근·미발행·실패·감사 행을 보존한다")
    void preservesOutboxBoundaryAndNonPublishedRecords() {
        // given
        var cutoff = NOW.minusDays(7);
        saveOutbox(1, "PUBLISHED", cutoff.minusNanos(1000));
        saveOutbox(2, "PUBLISHED", cutoff);
        saveOutbox(3, "PUBLISHED", cutoff.plusNanos(1000));
        saveOutbox(4, "PENDING", cutoff.minusDays(1));
        saveOutbox(5, "FAILED", cutoff.minusDays(1));
        saveOutbox(6, "PUBLISHED", null);

        // when
        var deleted = service.cleanPublishedEvents();

        // then
        assertThat(deleted).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("SELECT id FROM outbox_events ORDER BY id", Long.class))
            .containsExactly(2L, 3L, 4L, 5L, 6L);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_logs", Integer.class)).isEqualTo(1);
    }

    /**
     * 소비 완료 시각만 보관기간 판단에 사용하며 수신 시각과 다른 표의 기록은 건드리지 않는지 확인한다.
     */
    @Test
    @DisplayName("소비 기록은 14일보다 오래된 완료만 삭제하고 경계·최근·다른 기록은 보존한다")
    void usesProcessedTimeAndPreservesBoundary() {
        // given
        var cutoff = NOW.minusDays(14);
        saveProcessed(1, cutoff.minusNanos(1000), NOW);
        saveProcessed(2, cutoff, NOW.minusDays(30));
        saveProcessed(3, cutoff.plusNanos(1000), NOW.minusDays(30));
        saveOutbox(1, "PUBLISHED", NOW.minusDays(30));

        // when
        var deleted = service.cleanProcessedEvents();

        // then
        assertThat(deleted).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("SELECT id FROM processed_events ORDER BY id", Long.class))
            .containsExactly(2L, 3L);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_logs", Integer.class)).isEqualTo(1);
    }

    /**
     * 저장소 호출 한 번의 상한과 실행기의 후속 배치 반복을 실제 삭제 건수로 확인한다.
     */
    @Test
    @DisplayName("Outbox 삭제 한 배치는 1000건으로 제한하고 실행기는 남은 배치를 반복 처리한다")
    void limitsOutboxBatchAndRepeatsRemainingBatches() {
        // given
        transaction.executeWithoutResult(status -> {
            for (var id = 1; id <= 2001; id++) {
                saveOutbox(id, "PUBLISHED", NOW.minusDays(8));
            }
        });

        // when
        var firstBatch = transaction.execute(status -> store.deletePublishedBefore(NOW.minusDays(7)));

        // then
        assertThat(firstBatch).isEqualTo(1000);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(1001);

        // when
        var remaining = service.cleanPublishedEvents();

        // then
        assertThat(remaining).isEqualTo(1001);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isZero();
    }

    /**
     * 소비 기록도 동일한 1,000건 상한과 다음 배치 실행 경계를 적용하는지 확인한다.
     */
    @Test
    @DisplayName("소비 기록 삭제 한 배치는 1000건으로 제한하고 남은 기록도 후속 실행에서 삭제한다")
    void limitsProcessedBatch() {
        // given
        transaction.executeWithoutResult(status -> {
            for (var id = 1; id <= 1001; id++) {
                saveProcessed(id, NOW.minusDays(15), NOW.minusDays(15));
            }
        });

        // when
        var firstBatch = transaction.execute(status -> store.deleteProcessedBefore(NOW.minusDays(14)));

        // then
        assertThat(firstBatch).isEqualTo(1000);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM processed_events", Integer.class)).isEqualTo(1);

        // when
        var remaining = service.cleanProcessedEvents();

        // then
        assertThat(remaining).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM processed_events", Integer.class)).isZero();
    }

    /**
     * 두 번째 배치의 DB 실패가 첫 번째 커밋을 취소하지 않고 재기동 뒤 남은 기록만 처리하는지 확인한다.
     */
    @Test
    @DisplayName("중간 배치가 실패해도 앞선 삭제는 유지하고 재기동 뒤 남은 기록을 정리한다")
    void preservesCommittedBatchAndResumesAfterRestart() {
        // given
        transaction.executeWithoutResult(status -> {
            for (var id = 1; id <= 1001; id++) {
                saveOutbox(id, "PUBLISHED", NOW.minusDays(8));
            }
        });
        // 추가 DB 관리 권한 없이 두 번째 배치의 실제 DELETE만 실패시키는 테스트 전용 참조다.
        jdbcTemplate.execute("""
            CREATE TABLE cleanup_failure_probe (
                outbox_id BIGINT NOT NULL PRIMARY KEY,
                CONSTRAINT reject_cleanup FOREIGN KEY (outbox_id) REFERENCES outbox_events(id)
            )
            """);
        jdbcTemplate.update("INSERT INTO cleanup_failure_probe (outbox_id) VALUES (1001)");

        // when & then
        assertThatThrownBy(service::cleanPublishedEvents)
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("reject_cleanup");
        assertThat(jdbcTemplate.queryForList("SELECT id FROM outbox_events", Long.class)).containsExactly(1001L);

        // when
        jdbcTemplate.execute("DROP TABLE cleanup_failure_probe");
        context.close();
        openContext();
        var resumed = service.cleanPublishedEvents();

        // then
        assertThat(resumed).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isZero();
    }

    /**
     * 저장소를 트랜잭션 없이 직접 호출해 일부 삭제만 커밋되는 경로를 차단하는지 확인한다.
     */
    @Test
    @DisplayName("정리 저장소는 기존 트랜잭션이 없으면 삭제 전에 실패한다")
    void rejectsStoreCallsOutsideTransaction() {
        // given
        saveOutbox(1, "PUBLISHED", NOW.minusDays(8));
        saveProcessed(1, NOW.minusDays(15), NOW.minusDays(15));

        // when & then
        assertThatThrownBy(() -> store.deletePublishedBefore(NOW.minusDays(7)))
            .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> store.deleteProcessedBefore(NOW.minusDays(14)))
            .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM processed_events", Integer.class)).isEqualTo(1);
    }

    /**
     * 이미 커밋한 정리 배치가 호출자 트랜잭션의 나중 실패에 의해 취소되지 않는지 확인한다.
     */
    @Test
    @DisplayName("호출자 트랜잭션이 롤백돼도 별도로 커밋한 정리 결과는 유지한다")
    void commitsCleanupIndependentlyOfCallerTransaction() {
        // given
        saveOutbox(1, "PUBLISHED", NOW.minusDays(8));
        var failure = new IllegalStateException("caller failure");

        // when & then
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            assertThat(service.cleanPublishedEvents()).isEqualTo(1);
            throw failure;
        })).isSameAs(failure);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isZero();
    }

    /**
     * DB를 보존한 채 저장소·정리 실행기와 고정 Clock을 새 컨텍스트에 연결한다.
     */
    private void openContext() {
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean(PlatformTransactionManager.class, () -> new JdbcTransactionManager(dataSource));
        context
            .registerBean(
                "testClock",
                Clock.class,
                () -> Clock.fixed(Instant.parse("2026-10-30T00:00:00.123456789Z"), ZoneOffset.UTC),
                bean -> bean.setPrimary(true)
            );
        context.register(EventCleanupStorageConfiguration.class);
        context.refresh();
        transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        store = context.getBean(JdbcEventCleanupStore.class);
        service = context.getBean(EventRecordCleanupService.class);
    }

    /**
     * 생성 시각과 발행 완료 시각을 분리해 보관 조건을 검증할 테스트 행을 저장한다.
     *
     * @param id 명시적으로 지정할 테스트 PK
     * @param status 발행 상태
     * @param publishedAt 발행 완료 시각, 미발행 경계 검증에는 null 허용
     */
    private void saveOutbox(long id, String status, LocalDateTime publishedAt) {
        var createdAt = Timestamp.valueOf(NOW.minusDays(40));
        jdbcTemplate
            .update(
                """
                    INSERT INTO outbox_events (
                        id, event_id, event_type, schema_version, aggregate_type, aggregate_id,
                        correlation_id, payload, status, retry_count,
                        occurred_at, published_at, created_at, updated_at
                    ) VALUES (
                        ?, ?, 'SubmissionJudged', 'v1', 'SUBMISSION', '55', 'cleanup-test', '{}', ?, 0, ?, ?, ?, ?
                    )
                    """,
                id,
                UUID.randomUUID().toString(),
                status,
                createdAt,
                publishedAt == null ? null : Timestamp.valueOf(publishedAt),
                createdAt,
                createdAt
            );
    }

    /**
     * 수신 시각과 처리 완료 시각이 달라도 완료 시각으로만 정리하는지 확인할 소비 기록을 저장한다.
     *
     * @param id 명시적으로 지정할 테스트 PK
     * @param processedAt 소비 완료 시각
     * @param createdAt 최초 수신 시각
     */
    private void saveProcessed(long id, LocalDateTime processedAt, LocalDateTime createdAt) {
        jdbcTemplate.update("""
            INSERT INTO processed_events (id, event_id, event_type, processed_at, created_at)
            VALUES (?, ?, 'SubmissionJudged', ?, ?)
            """, id, UUID.randomUUID().toString(), Timestamp.valueOf(processedAt), Timestamp.valueOf(createdAt));
    }
}
