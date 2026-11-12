package com.backtoback.judge.judging.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

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

import com.backtoback.common.event.config.EventConsumerConfiguration;
import com.backtoback.common.event.service.EventConsumer;
import com.backtoback.common.scheduling.service.EventRecordCleanupService;
import com.backtoback.judge.global.config.JudgeEventCleanupConfiguration;
import com.backtoback.judge.global.config.PersistenceConfiguration;
import com.backtoback.judge.judging.service.ExamSubmissionRequestedHandler;

/** Judge 스키마의 보관 경계와 소비 기록 정리 이후의 업무 멱등성을 확인한다. */
@DataJpaTest(properties = "spring.jpa.show-sql=false")
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    {
        PersistenceConfiguration.class,
        JudgeEventCleanupConfiguration.class,
        EventConsumerConfiguration.class,
        ExamSubmissionRequestedHandler.class,
        ExternalSubmissionAcceptanceStore.class,
        SubmissionReconciliationStoreTest.Beans.class
    }
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class JudgeEventCleanupStoreTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EventRecordCleanupService cleanup;
    @Autowired
    private EventConsumer consumer;

    /**
     * 서비스 마이그레이션을 적용할 DB를 제공한다.
     *
     * @param registry 테스트 DB 연결 속성
     */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    /** 테스트별 소비·발행·제출 상태를 비운다. */
    @BeforeEach
    void prepare() {
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM processed_events");
        jdbc.update("DELETE FROM judge_jobs");
        jdbc.update("DELETE FROM submissions");
    }

    /** 완료 기록의 엄격한 보관 경계와 미발행 기록 보존을 확인한다. */
    @Test
    @DisplayName("7일 이전 발행 완료 기록만 정리하고 경계·PENDING·FAILED는 보존한다")
    void cleansOnlyExpiredPublishedEvents() {
        for (var status : new String[] {
            "PUBLISHED",
            "PENDING",
            "FAILED"
        }) {
            jdbc.update("""
                INSERT INTO outbox_events(event_id,event_type,schema_version,aggregate_type,aggregate_id,
                    correlation_id,payload,status,retry_count,occurred_at,published_at,created_at,updated_at)
                VALUES (UUID(),'SubmissionJudged','v1','Submission','1','test',JSON_OBJECT(),?,0,
                    '2026-10-01 09:00:00','2026-10-24 09:02:59.999999','2026-10-01 09:00:00',
                    '2026-10-01 09:00:00')
                """, status);
        }
        jdbc.update("""
            INSERT INTO outbox_events(event_id,event_type,schema_version,aggregate_type,aggregate_id,
                correlation_id,payload,status,retry_count,occurred_at,published_at,created_at,updated_at)
            VALUES (UUID(),'SubmissionJudged','v1','Submission','2','test',JSON_OBJECT(),'PUBLISHED',0,
                '2026-10-01 09:00:00','2026-10-24 09:03:00','2026-10-01 09:00:00','2026-10-01 09:00:00')
            """);
        assertThat(cleanup.cleanPublishedEvents()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(3);
    }

    /** 소비 멱등키 보관이 끝나도 원본 접수 ID로 업무 중복을 막는다. */
    @Test
    @DisplayName("소비 기록 정리 뒤 같은 시험 이벤트가 와도 최초 제출과 작업만 유지한다")
    void remainsIdempotentAfterConsumerHistoryCleanup() {
        String event = """
            {"eventId":"00000000-0000-4000-8000-000000000001","eventType":"ExamSubmissionRequested",
             "schemaVersion":"v1","occurredAt":"2026-10-01T09:00:00.000000+09:00",
             "aggregateId":"6","correlationId":"test","examId":"5","examSubmissionId":"6",
             "participantId":"8","userId":"7","problemId":"1","problemRevisionId":"1",
             "language":"java","sourceCode":"x","draftSeq":"0","submissionType":"MANUAL",
             "receivedAt":"2026-10-01T09:00:00.000000+09:00"}
            """;
        var acknowledgements = new AtomicInteger();
        consumer.consume(event, acknowledgements::incrementAndGet);
        jdbc.update("UPDATE processed_events SET processed_at='2026-10-17 09:02:59.999999'");
        assertThat(cleanup.cleanProcessedEvents()).isEqualTo(1);
        consumer.consume(event, acknowledgements::incrementAndGet);
        assertThat(acknowledgements.get()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM submissions", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM judge_jobs", Integer.class)).isEqualTo(1);
    }
}
