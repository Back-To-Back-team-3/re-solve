package com.backtoback.common.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
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

import com.backtoback.common.event.config.OutboxStorageConfiguration;
import com.backtoback.common.event.dto.EventMetadata;
import com.backtoback.common.time.ClockConfiguration;

import tools.jackson.databind.ObjectMapper;

/**
 * 실제 MySQL과 Spring 트랜잭션 프록시로 업무 변경·Outbox의 원자성을 검증한다.
 * Docker가 없으면 실행하지 않으며 테스트 스키마는 서비스의 Flyway 이력과 분리한다.
 */
@Testcontainers(disabledWithoutDocker = true)
class OutboxEventWriterTest {
    @Container
    private static final MySQLContainer DATABASE
        = new MySQLContainer("mysql:8.4")
            // 포트 개방 직후 MySQL 초기 응답이 없으면 Testcontainers가 준비 확인을 다시 시도하도록 한다.
            .withUrlParam("connectTimeout", "5000")
            .withUrlParam("socketTimeout", "10000");

    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transaction;
    private OutboxEventWriter writer;

    /**
     * 서비스가 제공할 DB·트랜잭션 관리자·JSON 매퍼와 고정 Clock을 연결한다.
     * 공통 저장 설정만 등록하므로 Relay나 예약 작업 없이 동작해야 한다.
     */
    @BeforeEach
    void setUp() {
        var dataSource
            = new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP TABLE IF EXISTS business_records");
        jdbcTemplate.execute("DROP TABLE IF EXISTS outbox_events");
        new ResourceDatabasePopulator(new ClassPathResource("db/outbox-test-schema.sql")).execute(dataSource);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        context.registerBean(PlatformTransactionManager.class, () -> new JdbcTransactionManager(dataSource));
        context
            .registerBean(
                "testClock",
                Clock.class,
                () -> Clock.fixed(Instant.parse("2026-10-29T12:00:00.123456789Z"), ZoneOffset.UTC),
                beanDefinition -> beanDefinition.setPrimary(true)
            );
        context.register(OutboxStorageConfiguration.class);
        context.refresh();
        transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        writer = context.getBean(OutboxEventWriter.class);
    }

    /**
     * 테스트마다 생성한 Spring Bean과 트랜잭션 설정을 종료한다.
     */
    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    /**
     * 커밋 후 별도 연결로 업무 행과 JSON 컬럼을 읽고 저장 시각과 초기 상태를 대조한다.
     */
    @Test
    @DisplayName("업무 변경과 Outbox를 함께 커밋하고 발행 대기 상태와 같은 이벤트 ID를 저장한다")
    void commitsBusinessAndOutboxTogether() {
        // given
        var expectedTime = LocalDateTime.parse("2026-10-29T21:00:00.123456");

        // when
        var metadata = transaction.execute(status -> {
            jdbcTemplate.update("INSERT INTO business_records (id, status) VALUES (55, 'ACCEPTED')");
            return appendEvent();
        });
        var saved = jdbcTemplate.queryForMap("SELECT * FROM outbox_events");

        // then
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM business_records", Integer.class)).isEqualTo(1);
        assertThat(saved.get("event_id")).isEqualTo(metadata.eventId().toString());
        assertThat(saved.get("aggregate_type")).isEqualTo("SUBMISSION");
        assertThat(saved.get("status")).isEqualTo("PENDING");
        assertThat(saved.get("retry_count")).isEqualTo(0);
        assertThat(saved.get("published_at")).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT occurred_at FROM outbox_events", LocalDateTime.class))
            .isEqualTo(expectedTime);
        assertThat(jdbcTemplate.queryForObject("SELECT created_at FROM outbox_events", LocalDateTime.class))
            .isEqualTo(expectedTime);
        assertThat(jdbcTemplate.queryForObject("SELECT updated_at FROM outbox_events", LocalDateTime.class))
            .isEqualTo(expectedTime);
        var mapper = context.getBean(ObjectMapper.class);
        var message = mapper.readTree(jdbcTemplate.queryForObject("SELECT payload FROM outbox_events", String.class));
        assertThat(message.get("eventId")).isEqualTo(mapper.valueToTree(metadata.eventId().toString()));
        assertThat(message.has("aggregate_type")).isFalse();
    }

    /**
     * 이벤트 저장까지 끝났더라도 뒤따르는 업무 실패가 두 테이블의 변경을 모두 취소하는지 확인한다.
     */
    @Test
    @DisplayName("Outbox 저장 뒤 업무가 실패하면 업무 변경과 이벤트를 모두 롤백한다")
    void rollsBackBusinessAndOutboxTogether() {
        // given
        var failure = new IllegalStateException("business failure");

        // when & then
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            jdbcTemplate.update("INSERT INTO business_records (id, status) VALUES (55, 'ACCEPTED')");
            appendEvent();
            throw failure;
        })).isSameAs(failure);
        assertThat(countRecords("business_records")).isZero();
        assertThat(countRecords("outbox_events")).isZero();
    }

    /**
     * 업무 트랜잭션 없이 이벤트만 커밋되는 경로가 프록시에서 차단되는지 확인한다.
     */
    @Test
    @DisplayName("업무 트랜잭션 밖에서 Outbox 저장을 요청하면 기록 전에 실패한다")
    void rejectsAppendWithoutBusinessTransaction() {
        // when & then
        assertThatThrownBy(this::appendEvent).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(countRecords("business_records")).isZero();
        assertThat(countRecords("outbox_events")).isZero();
    }

    /**
     * 공통 헤더와 충돌하는 업무 payload를 거절했을 때 앞선 업무 INSERT도 함께 취소하는지 확인한다.
     */
    @Test
    @DisplayName("이벤트 직렬화가 실패하면 앞선 업무 변경도 롤백한다")
    void rollsBackBusinessWhenSerializationFails() {
        // given
        var payload = Map.of("eventId", "override");

        // when & then
        assertThatIllegalArgumentException().isThrownBy(() -> transaction.executeWithoutResult(status -> {
            jdbcTemplate.update("INSERT INTO business_records (id, status) VALUES (55, 'ACCEPTED')");
            writer.append("SUBMISSION", "SubmissionJudged", "v1", "55", "request-test", payload);
        }));
        assertThat(countRecords("business_records")).isZero();
        assertThat(countRecords("outbox_events")).isZero();
    }

    /**
     * 실제 DB 제약으로 Outbox INSERT를 실패시켜 업무 행만 커밋되는 경우가 없는지 확인한다.
     * MySQL CHECK 오류의 Spring 하위 예외 분류 대신 발생시킨 제약과 JDBC 원인을 확인한다.
     */
    @Test
    @DisplayName("Outbox INSERT가 DB에서 실패하면 앞선 업무 변경을 롤백한다")
    void rollsBackBusinessWhenOutboxInsertFails() {
        // given
        jdbcTemplate.execute("""
            ALTER TABLE outbox_events ADD CONSTRAINT reject_test_event CHECK (event_type <> 'SubmissionJudged')
            """);

        // when & then
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            jdbcTemplate.update("INSERT INTO business_records (id, status) VALUES (55, 'ACCEPTED')");
            appendEvent();
        }))
            .isInstanceOf(DataAccessException.class)
            .hasCauseInstanceOf(SQLException.class)
            .hasMessageContaining("reject_test_event");
        assertThat(countRecords("business_records")).isZero();
        assertThat(countRecords("outbox_events")).isZero();
    }

    /**
     * 같은 애그리게이트의 서로 다른 업무 발생에는 서로 다른 v4 ID가 발급되는지 확인한다.
     */
    @Test
    @DisplayName("같은 업무 대상의 새 이벤트는 각각 다른 UUID v4로 기록한다")
    void assignsDifferentIdsToNewEvents() {
        // when
        var first = transaction.execute(status -> appendEvent());
        var second = transaction.execute(status -> appendEvent());

        // then
        assertThat(first.eventId()).isNotEqualTo(second.eventId());
        assertThat(first.eventId().version()).isEqualTo(4);
        assertThat(second.eventId().version()).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(2);
    }

    /**
     * 공통 헤더를 포함한 UTF-8 메시지가 상한과 정확히 같으면 업무 변경과 함께 저장한다.
     */
    @Test
    @DisplayName("직렬화한 전체 메시지가 정확히 256KB이면 업무 변경과 Outbox를 함께 저장한다")
    void acceptsMessageAtByteLimit() {
        // given
        var payload = payloadWithMessageSize(256 * 1024);
        var message = context.getBean(EventJsonCodec.class).serialize(sizeTestMetadata(), payload);
        assertThat(message.getBytes(StandardCharsets.UTF_8)).hasSize(256 * 1024);

        // when
        var metadata = transaction.execute(status -> {
            jdbcTemplate.update("INSERT INTO business_records (id, status) VALUES (55, 'ACCEPTED')");
            return writer.append("SUBMISSION", "SubmissionJudged", "v1", "55", "request-test", payload);
        });

        // then
        assertThat(countRecords("business_records")).isEqualTo(1);
        assertThat(countRecords("outbox_events")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT event_id FROM outbox_events", String.class))
            .isEqualTo(metadata.eventId().toString());
    }

    /**
     * 한글을 포함해 문자 수는 상한보다 적어도 UTF-8 바이트 수가 초과하면 앞선 업무 변경도 취소한다.
     */
    @Test
    @DisplayName("UTF-8 메시지가 256KB보다 1바이트 크면 Outbox에 저장하지 않고 업무 변경도 롤백한다")
    void rejectsOversizedUtf8MessageAndRollsBackBusiness() {
        // given
        var payload = payloadWithMessageSize(256 * 1024 + 1);
        var message = context.getBean(EventJsonCodec.class).serialize(sizeTestMetadata(), payload);
        assertThat(message.length()).isLessThan(256 * 1024);
        assertThat(message.getBytes(StandardCharsets.UTF_8)).hasSize(256 * 1024 + 1);

        // when & then
        assertThatIllegalArgumentException().isThrownBy(() -> transaction.executeWithoutResult(status -> {
            jdbcTemplate.update("INSERT INTO business_records (id, status) VALUES (55, 'ACCEPTED')");
            writer.append("SUBMISSION", "SubmissionJudged", "v1", "55", "request-test", payload);
        })).withMessage("Serialized event message exceeds 256KB in UTF-8");
        assertThat(countRecords("business_records")).isZero();
        assertThat(countRecords("outbox_events")).isZero();
    }

    /**
     * 실제 기록 경로와 길이가 같은 공통 헤더를 사용해 한글을 포함한 경계 크기의 payload를 만든다.
     *
     * @param messageBytes 공통 헤더를 포함한 직렬화 메시지의 목표 UTF-8 바이트 수
     * @return 목표 크기를 채우는 업무 필드
     */
    private Map<String, String> payloadWithMessageSize(int messageBytes) {
        var codec = context.getBean(EventJsonCodec.class);
        var headerBytes
            = codec.serialize(sizeTestMetadata(), Map.of("content", "")).getBytes(StandardCharsets.UTF_8).length;
        var contentBytes = messageBytes - headerBytes;
        return Map.of("content", "가".repeat(contentBytes / 3) + "a".repeat(contentBytes % 3));
    }

    /**
     * 무작위 UUID의 고정 길이와 주입한 시각을 반영해 메시지 경계 계산에 사용할 헤더를 만든다.
     *
     * @return 실제 저장 시와 같은 길이로 직렬화되는 공통 메타데이터
     */
    private EventMetadata sizeTestMetadata() {
        return new EventMetadata(
            UUID.fromString("d65b1858-9741-465f-90f7-5af96ad7bd60"),
            "SubmissionJudged",
            "v1",
            OffsetDateTime.ofInstant(context.getBean(Clock.class).instant(), ClockConfiguration.SERVICE_ZONE),
            "55",
            "request-test"
        );
    }

    /**
     * 테스트에서 공통으로 사용할 이벤트를 현재 트랜잭션의 DB에 저장한다.
     *
     * @return 저장한 이벤트의 메타데이터
     */
    private EventMetadata appendEvent() {
        return writer
            .append(
                "SUBMISSION",
                "SubmissionJudged",
                "v1",
                "55",
                "request-test",
                Map.of("submissionId", "55", "judgeAttempt", 1)
            );
    }

    /**
     * 트랜잭션 종료 후 별도 연결에서 테스트 테이블의 행 수만 조회한다.
     * 허용한 테이블명만 SQL에 사용하고 결과에 대한 핵심 assertion은 테스트 본문에서 수행한다.
     *
     * @param tableName 조회할 테스트 테이블 이름
     * @return 커밋되어 남아 있는 행 수
     * @throws IllegalArgumentException 테스트에서 허용하지 않은 테이블 이름일 때
     */
    private int countRecords(String tableName) {
        if (!"business_records".equals(tableName) && !"outbox_events".equals(tableName)) {
            throw new IllegalArgumentException("Unsupported test table: " + tableName);
        }
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Integer.class);
    }
}
