package com.backtoback.common.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.event.config.EventConsumerConfiguration;
import com.backtoback.common.event.dto.EventMetadata;
import com.backtoback.common.event.dto.ReceivedEvent;

import tools.jackson.databind.ObjectMapper;

/**
 * 실제 MySQL과 서비스 업무 핸들러를 연결해 소비 원자성·중복 확인·커밋 후 ACK를 검증한다.
 * 메시지 삭제는 callback으로 대체하며 실제 큐의 Visibility·DLQ 구성은 서비스 검증 범위에 남긴다.
 */
@Testcontainers(disabledWithoutDocker = true)
class EventConsumerTest {
    @Container
    private static final MySQLContainer DATABASE
        = new MySQLContainer("mysql:8.4")
            // 시작 중 응답 없는 초기 연결이 소비 테스트를 무기한 대기시키지 않도록 한다.
            .withUrlParam("connectTimeout", "5000")
            .withUrlParam("socketTimeout", "10000");

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger handlerCalls = new AtomicInteger();
    private final AtomicInteger acknowledgements = new AtomicInteger();

    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbcTemplate;
    private EventConsumer eventConsumer;
    private Consumer<ReceivedEvent> handlerAction;

    /**
     * 소비 설정만 등록해 테스트 스키마·서비스 업무 처리·고정 Clock을 연결한다.
     */
    @BeforeEach
    void setUp() {
        var dataSource
            = new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP TABLE IF EXISTS consumer_business_records");
        jdbcTemplate.execute("DROP TABLE IF EXISTS processed_events");
        new ResourceDatabasePopulator(new ClassPathResource("db/consumer-test-schema.sql")).execute(dataSource);
        handlerAction = this::insertBusinessRecord;
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean(ObjectMapper.class, () -> mapper);
        context.registerBean(PlatformTransactionManager.class, () -> new JdbcTransactionManager(dataSource));
        context
            .registerBean(
                "testClock",
                Clock.class,
                () -> Clock.fixed(Instant.parse("2026-10-29T12:00:00.123456789Z"), ZoneOffset.UTC),
                beanDefinition -> beanDefinition.setPrimary(true)
            );
        context.registerBean(EventHandler.class, () -> new TestHandler());
        context.register(EventConsumerConfiguration.class);
        context.refresh();
        eventConsumer = context.getBean(EventConsumer.class);
    }

    /**
     * 생성한 Bean과 테스트 스레드의 추적 정보를 정리한다.
     */
    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
        MDC.clear();
    }

    /**
     * ACK에서 새 연결로 조회해 업무와 소비 기록이 이미 커밋됐는지 확인한다.
     */
    @Test
    @DisplayName("업무와 소비 기록을 먼저 커밋하고 트랜잭션 밖에서 ACK한다")
    void acknowledgesOnlyAfterBusinessAndProcessedRecordCommit() {
        // given
        var metadata = metadata("SubmissionJudged", "v1");
        handlerAction = event -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(processedCount(event.metadata())).isEqualTo(1);
            insertBusinessRecord(event);
        };

        // when
        eventConsumer.consume(message(metadata, 55, "business-55"), () -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(processedCount(metadata)).isEqualTo(1);
            assertThat(businessCount()).isEqualTo(1);
            acknowledgements.incrementAndGet();
        });

        // then
        assertThat(handlerCalls.get()).isEqualTo(1);
        assertThat(acknowledgements.get()).isEqualTo(1);
    }

    /**
     * 같은 ID의 완료 기록을 새 경계에서 확인한 재수신은 업무를 반복하지 않고 ACK하는지 확인한다.
     */
    @Test
    @DisplayName("이미 커밋한 이벤트의 재수신은 업무를 재실행하지 않고 ACK한다")
    void acknowledgesCompletedDuplicateWithoutRepeatingBusiness() {
        // given
        var metadata = metadata("SubmissionJudged", "v1");
        var message = message(metadata, 55, "business-55");
        eventConsumer.consume(message, acknowledgements::incrementAndGet);

        // when
        eventConsumer.consume(message, acknowledgements::incrementAndGet);

        // then
        assertThat(handlerCalls.get()).isEqualTo(1);
        assertThat(processedCount(metadata)).isEqualTo(1);
        assertThat(businessCount()).isEqualTo(1);
        assertThat(acknowledgements.get()).isEqualTo(2);
    }

    /**
     * 업무 INSERT 후 실패해도 소비 기록까지 취소되어 다음 재전달이 다시 업무를 수행할 수 있는지 확인한다.
     */
    @Test
    @DisplayName("업무 실패 시 소비 기록과 업무를 함께 롤백하고 ACK하지 않는다")
    void rollsBackProcessedRecordAndBusinessOnHandlerFailure() {
        // given
        var metadata = metadata("SubmissionJudged", "v1");
        handlerAction = event -> {
            insertBusinessRecord(event);
            throw new IllegalStateException("simulated business failure");
        };

        // when & then
        assertThatThrownBy(
            () -> eventConsumer.consume(message(metadata, 55, "business-55"), acknowledgements::incrementAndGet)
        ).isInstanceOf(IllegalStateException.class);
        assertThat(processedCount(metadata)).isZero();
        assertThat(businessCount()).isZero();
        assertThat(acknowledgements.get()).isZero();
    }

    /**
     * 업무 테이블의 UNIQUE 오류를 소비 기록 중복으로 바꿔 ACK하지 않는지 확인한다.
     */
    @Test
    @DisplayName("업무 UNIQUE 위반은 이벤트 중복 성공으로 처리하지 않고 소비 기록을 롤백한다")
    void propagatesBusinessUniqueFailureWithoutAcknowledgement() {
        // given
        var metadata = metadata("SubmissionJudged", "v1");
        jdbcTemplate.update("""
            INSERT INTO consumer_business_records (id, business_key, status) VALUES (1, ?, 'EXISTING')
            """, "same-business-key");

        // when & then
        assertThatThrownBy(
            () -> eventConsumer.consume(message(metadata, 55, "same-business-key"), acknowledgements::incrementAndGet)
        ).isInstanceOf(DuplicateKeyException.class);
        assertThat(processedCount(metadata)).isZero();
        assertThat(businessCount()).isEqualTo(1);
        assertThat(handlerCalls.get()).isEqualTo(1);
        assertThat(acknowledgements.get()).isZero();
    }

    /**
     * 소비 테이블의 다른 유일 제약이 실패해도 해당 ID의 실제 완료 기록 없이는 ACK하지 않는다.
     */
    @Test
    @DisplayName("소비 기록 INSERT가 중복 오류여도 해당 이벤트 ID 완료가 없으면 ACK하지 않는다")
    void rejectsDuplicateInsertWithoutCompletionForSameEventId() {
        // given
        var completed = metadata("SubmissionJudged", "v1");
        var incoming = metadata("SubmissionJudged", "v1");
        eventConsumer.consume(message(completed, 55, "business-55"), acknowledgements::incrementAndGet);
        jdbcTemplate.execute("ALTER TABLE processed_events ADD CONSTRAINT test_event_type_unique UNIQUE (event_type)");

        // when & then
        assertThatThrownBy(
            () -> eventConsumer.consume(message(incoming, 56, "business-56"), acknowledgements::incrementAndGet)
        ).isInstanceOf(DuplicateKeyException.class);
        assertThat(processedCount(completed)).isEqualTo(1);
        assertThat(processedCount(incoming)).isZero();
        assertThat(handlerCalls.get()).isEqualTo(1);
        assertThat(businessCount()).isEqualTo(1);
        assertThat(acknowledgements.get()).isEqualTo(1);
    }

    /**
     * 업무가 반환된 뒤 커밋 단계에서 실패해도 ACK를 실행하지 않는 실제 트랜잭션 경계를 확인한다.
     */
    @Test
    @DisplayName("핸들러 반환 뒤 커밋이 실패해도 ACK하지 않고 소비 기록과 업무를 롤백한다")
    void doesNotAcknowledgeCommitFailure() {
        // given
        var metadata = metadata("SubmissionJudged", "v1");
        handlerAction = event -> {
            insertBusinessRecord(event);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                /**
                 * 업무 처리가 끝난 뒤 커밋 직전 실패를 발생시킨다.
                 *
                 * @param readOnly 현재 트랜잭션의 읽기 전용 여부
                 */
                @Override
                public void beforeCommit(boolean readOnly) {
                    throw new IllegalStateException("simulated commit failure");
                }
            });
        };

        // when & then
        assertThatThrownBy(
            () -> eventConsumer.consume(message(metadata, 55, "business-55"), acknowledgements::incrementAndGet)
        ).isInstanceOf(IllegalStateException.class).hasMessage("simulated commit failure");
        assertThat(processedCount(metadata)).isZero();
        assertThat(businessCount()).isZero();
        assertThat(acknowledgements.get()).isZero();
    }

    /**
     * ACK만 실패했을 때 완료 기록을 취소하지 않아 재수신에서 업무가 반복되지 않는지 확인한다.
     */
    @Test
    @DisplayName("ACK 실패 후 재수신은 커밋된 소비 기록으로 흡수하고 업무를 반복하지 않는다")
    void preservesCompletionWhenAcknowledgementFails() {
        // given
        var metadata = metadata("SubmissionJudged", "v1");
        var message = message(metadata, 55, "business-55");

        // when & then: 첫 ACK 실패 시점에는 업무와 소비 기록이 이미 커밋되어 있어야 한다.
        assertThatThrownBy(() -> eventConsumer.consume(message, () -> {
            throw new IllegalStateException("simulated acknowledgement failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(processedCount(metadata)).isEqualTo(1);
        assertThat(businessCount()).isEqualTo(1);

        // when
        eventConsumer.consume(message, acknowledgements::incrementAndGet);

        // then
        assertThat(handlerCalls.get()).isEqualTo(1);
        assertThat(acknowledgements.get()).isEqualTo(1);
    }

    /**
     * 관심 없는 이벤트는 업무 핸들러 없이 기록을 남기고, 아는 종류의 미지원 버전은 롤백하는지 확인한다.
     */
    @Test
    @DisplayName("모르는 종류는 기록 후 ACK하고 등록된 종류의 미지원 버전은 기록을 롤백한다")
    void distinguishesUnknownTypeAndUnsupportedVersion() {
        // given
        var unknown = metadata("MemberUpdated", "v1");
        var unsupported = metadata("SubmissionJudged", "v2");

        // when
        eventConsumer.consume(message(unknown, 55, "business-55"), acknowledgements::incrementAndGet);

        // then
        assertThat(processedCount(unknown)).isEqualTo(1);
        assertThat(handlerCalls.get()).isZero();
        assertThat(businessCount()).isZero();
        assertThat(acknowledgements.get()).isEqualTo(1);

        // when & then
        assertThatThrownBy(
            () -> eventConsumer.consume(message(unsupported, 56, "business-56"), acknowledgements::incrementAndGet)
        ).isInstanceOf(IllegalArgumentException.class);
        assertThat(processedCount(unsupported)).isZero();
        assertThat(acknowledgements.get()).isEqualTo(1);
    }

    /**
     * 성공·업무 실패·ACK 실패 경로에서 이벤트 추적 정보가 이전 스레드 문맥으로 복원되는지 확인한다.
     */
    @Test
    @DisplayName("업무 처리 중 이벤트 MDC를 연결하고 성공과 실패 후 이전 문맥을 복원한다")
    void restoresMdcAfterSuccessAndFailures() {
        // given
        var previousContext = Map.of("correlationId", "previous-request", "requestContext", "keep");
        MDC.setContextMap(previousContext);
        handlerAction = event -> {
            assertThat(MDC.get("eventId")).isEqualTo(event.metadata().eventId().toString());
            assertThat(MDC.get("eventType")).isEqualTo("SubmissionJudged");
            assertThat(MDC.get("correlationId")).isEqualTo("request-test");
            insertBusinessRecord(event);
        };
        var success = metadata("SubmissionJudged", "v1");

        // when
        eventConsumer.consume(message(success, 55, "business-55"), acknowledgements::incrementAndGet);

        // then
        assertThat(MDC.getCopyOfContextMap()).isEqualTo(previousContext);

        // when & then: 업무 실패 후 복원도 확인한다.
        handlerAction = event -> {
            throw new IllegalStateException("simulated business failure");
        };
        var failed = metadata("SubmissionJudged", "v1");
        assertThatThrownBy(
            () -> eventConsumer.consume(message(failed, 56, "business-56"), acknowledgements::incrementAndGet)
        ).isInstanceOf(IllegalStateException.class);
        assertThat(MDC.getCopyOfContextMap()).isEqualTo(previousContext);

        // when & then: 이미 완료한 이벤트의 ACK가 실패해도 복원한다.
        assertThatThrownBy(() -> eventConsumer.consume(message(success, 55, "business-55"), () -> {
            throw new IllegalStateException("simulated acknowledgement failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(MDC.getCopyOfContextMap()).isEqualTo(previousContext);
    }

    /**
     * 동일 이벤트를 두 스레드에서 겹쳐 전달해 먼저 커밋한 업무만 한 번 반영되는지 확인한다.
     *
     * @throws Exception 스레드 동기화 또는 제한 시간 내 작업 완료에 실패할 때
     */
    @Test
    @DisplayName("동시 중복 수신은 업무를 한 번만 반영하고 두 메시지 모두 커밋 후 ACK한다")
    void absorbsConcurrentDuplicateAfterFirstCommit() throws Exception {
        // given
        var metadata = metadata("SubmissionJudged", "v1");
        var message = message(metadata, 55, "business-55");
        var firstEntered = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        handlerAction = event -> {
            insertBusinessRecord(event);
            firstEntered.countDown();
            await(release);
        };

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> eventConsumer.consume(message, acknowledgements::incrementAndGet));
            try {
                assertThat(firstEntered.await(10, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> {
                    secondStarted.countDown();
                    eventConsumer.consume(message, acknowledgements::incrementAndGet);
                });
                assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();

                // when
                release.countDown();
                first.get(15, TimeUnit.SECONDS);
                second.get(15, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }

        // then
        assertThat(handlerCalls.get()).isEqualTo(1);
        assertThat(processedCount(metadata)).isEqualTo(1);
        assertThat(businessCount()).isEqualTo(1);
        assertThat(acknowledgements.get()).isEqualTo(2);
    }

    /**
     * 먼저 선점한 처리가 롤백하면 대기 중인 중복 수신이 새 처리자로 업무를 커밋할 수 있어야 한다.
     *
     * @throws Exception 스레드 동기화 또는 제한 시간 내 작업 완료에 실패할 때
     */
    @Test
    @DisplayName("먼저 수신한 처리가 롤백하면 동시 수신한 메시지가 업무와 소비 기록을 커밋한다")
    void allowsConcurrentReceiverAfterFirstRollback() throws Exception {
        // given
        var metadata = metadata("SubmissionJudged", "v1");
        var message = message(metadata, 55, "business-55");
        var firstEntered = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        handlerAction = event -> {
            insertBusinessRecord(event);
            if (handlerCalls.get() == 1) {
                firstEntered.countDown();
                await(release);
                throw new IllegalStateException("first receiver rolls back");
            }
        };

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> eventConsumer.consume(message, acknowledgements::incrementAndGet));
            try {
                assertThat(firstEntered.await(10, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> {
                    secondStarted.countDown();
                    eventConsumer.consume(message, acknowledgements::incrementAndGet);
                });
                assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();

                // when & then
                release.countDown();
                assertThatThrownBy(() -> first.get(15, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
                second.get(15, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }

        // then
        assertThat(handlerCalls.get()).isEqualTo(2);
        assertThat(processedCount(metadata)).isEqualTo(1);
        assertThat(businessCount()).isEqualTo(1);
        assertThat(acknowledgements.get()).isEqualTo(1);
    }

    /**
     * 소비 전용 구성이 Outbox 저장·Relay·예약 실행 설정을 요구하지 않는지 확인한다.
     */
    @Test
    @DisplayName("소비 설정만 등록해도 동작하고 Outbox 저장과 Relay 예약 실행은 활성화하지 않는다")
    void keepsConsumerConfigurationIndependent() {
        // then
        assertThat(context.getBeanNamesForType(OutboxEventWriter.class)).isEmpty();
        assertThat(context.getBeanNamesForType(OutboxRelay.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
    }

    /**
     * 콜백에 전달된 업무 값으로 실제 DB 행을 저장한다.
     *
     * @param event 수신 메타데이터와 업무 필드
     */
    private void insertBusinessRecord(ReceivedEvent event) {
        jdbcTemplate
            .update(
                "INSERT INTO consumer_business_records (id, business_key, status) VALUES (?, ?, 'DONE')",
                Long.parseLong(event.payload().get("recordId").asString()),
                event.payload().get("businessKey").asString()
            );
    }

    /**
     * 테스트별로 새로운 수신 ID를 발급하고 종류·버전만 원하는 시나리오에 맞춘다.
     *
     * @param eventType 등록 또는 미등록 이벤트 종류
     * @param schemaVersion 지원 여부를 검증할 형식 버전
     * @return 고정 발생 시각을 가진 새 이벤트 메타데이터
     */
    private EventMetadata metadata(String eventType, String schemaVersion) {
        return new EventMetadata(
            UUID.randomUUID(),
            eventType,
            schemaVersion,
            OffsetDateTime.parse("2026-10-29T21:00:00.123456+09:00"),
            "55",
            "request-test"
        );
    }

    /**
     * 공통 발행 직렬화기를 사용해 실제 소비기에 전달할 Raw 메시지를 만든다.
     *
     * @param metadata 수신할 이벤트의 공통 헤더
     * @param recordId 업무 INSERT에 사용할 테스트 PK
     * @param businessKey 이벤트 ID와 다른 업무 유일 키
     * @return 공통 헤더와 테스트 업무 필드를 합친 JSON
     */
    private String message(EventMetadata metadata, long recordId, String businessKey) {
        return new EventJsonCodec(mapper)
            .serialize(metadata, Map.of("recordId", Long.toString(recordId), "businessKey", businessKey));
    }

    /**
     * 현재 이벤트 ID의 커밋된 소비 기록 수만 조회한다.
     *
     * @param metadata 조회할 수신 이벤트
     * @return 해당 이벤트 ID로 커밋된 행 수
     */
    private int processedCount(EventMetadata metadata) {
        return jdbcTemplate
            .queryForObject(
                "SELECT COUNT(*) FROM processed_events WHERE event_id = ?",
                Integer.class,
                metadata.eventId().toString()
            );
    }

    /**
     * 새 연결에서 커밋되어 보이는 업무 행 수만 조회한다.
     *
     * @return 커밋된 테스트 업무 행 수
     */
    private int businessCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM consumer_business_records", Integer.class);
    }

    /**
     * 경합 테스트의 업무 완료 시점을 제어하고 실패 시 무기한 대기를 방지한다.
     *
     * @param release 메인 테스트가 업무 완료를 허용할 신호
     * @throws IllegalStateException 제한 시간 안에 신호가 없거나 인터럽트됐을 때
     */
    private void await(CountDownLatch release) {
        try {
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the consumer test");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Consumer test was interrupted", failure);
        }
    }

    /**
     * 테스트마다 원하는 업무 동작을 같은 소비 트랜잭션 안에서 실행하는 서비스 핸들러다.
     */
    private class TestHandler implements EventHandler {
        /**
         * 테스트 서비스가 처리하는 이벤트 종류를 제공한다.
         *
         * @return 판정 완료 이벤트명
         */
        @Override
        public String eventType() {
            return "SubmissionJudged";
        }

        /**
         * 버전 분리 검증을 위해 하나의 형식 버전만 등록한다.
         *
         * @return 이 테스트 핸들러가 지원하는 버전
         */
        @Override
        public String schemaVersion() {
            return "v1";
        }

        /**
         * INSERT 선점 이후에만 호출 횟수를 증가시키고 현재 테스트의 업무 처리를 수행한다.
         *
         * @param event 공통 형식 검사를 통과한 서비스 업무 이벤트
         */
        @Override
        public void handle(ReceivedEvent event) {
            handlerCalls.incrementAndGet();
            handlerAction.accept(event);
        }
    }
}
