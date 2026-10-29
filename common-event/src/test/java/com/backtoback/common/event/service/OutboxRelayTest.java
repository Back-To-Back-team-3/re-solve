package com.backtoback.common.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.event.client.AwsEventPublisher;
import com.backtoback.common.event.config.OutboxStorageConfiguration;
import com.backtoback.common.event.dto.EventMetadata;
import com.backtoback.common.event.dto.OutboxEvent;
import com.backtoback.common.event.store.JdbcOutboxStore;

import software.amazon.awssdk.core.exception.SdkClientException;
import tools.jackson.databind.ObjectMapper;

/**
 * 실제 MySQL의 SKIP LOCKED·상태 전이·재시도와 발행 후 업무 갱신의 커밋 경계를 검증한다.
 * AWS 응답만 Mock으로 대체하고 자동 예약 실행을 켜지 않아 각 회차를 직접 제어한다.
 */
@ExtendWith(MockitoExtension.class)
@Testcontainers(disabledWithoutDocker = true)
class OutboxRelayTest {
    @Container
    private static final MySQLContainer DATABASE
        = new MySQLContainer("mysql:8.4")
            // 컨테이너 시작 중 첫 연결이 응답하지 않아도 준비 확인 재시도가 진행되도록 한다.
            .withUrlParam("connectTimeout", "5000")
            .withUrlParam("socketTimeout", "10000");

    @Mock
    private AwsEventPublisher publisher;

    private final AtomicBoolean failAfterBusinessUpdate = new AtomicBoolean();

    private DataSource dataSource;
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transaction;
    private OutboxEventWriter writer;
    private OutboxRelay relay;

    /**
     * 테스트 전용 스키마를 초기화하고 수동 실행할 Relay와 서비스 업무 콜백을 연결한다.
     */
    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP TABLE IF EXISTS business_records");
        jdbcTemplate.execute("DROP TABLE IF EXISTS outbox_events");
        new ResourceDatabasePopulator(new ClassPathResource("db/outbox-test-schema.sql")).execute(dataSource);
        openContext();
    }

    /**
     * 회차마다 만든 Spring Bean을 종료한다. DB는 테스트 컨테이너 수명 동안 유지한다.
     */
    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    /**
     * 외부 발행 응답 뒤 Outbox 완료와 서비스의 조건부 업무 변경이 함께 커밋되는지 확인한다.
     */
    @Test
    @DisplayName("발행 성공 시 Outbox 완료와 서비스 업무 상태를 같은 트랜잭션으로 커밋한다")
    void commitsPublishedStateAndBusinessCallbackTogether() {
        // given
        saveBusinessRecord(55, "ACCEPTED");
        var metadata = saveEvent(55);

        // when
        var attempted = relay.relayBatch();

        // then
        assertThat(attempted).isEqualTo(1);
        assertThat(outboxStatus(metadata)).isEqualTo("PUBLISHED");
        assertThat(businessStatus(55)).isEqualTo("REQUESTED");
        assertThat(retryCount(metadata)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT published_at FROM outbox_events", LocalDateTime.class))
            .isEqualTo(LocalDateTime.parse("2026-10-29T21:00:00.123456"));
        verify(publisher).publish(any(OutboxEvent.class));
    }

    /**
     * 결과 소비가 먼저 종결한 업무 행은 성공 콜백의 조건부 UPDATE가 덮어쓰지 않는지 확인한다.
     */
    @Test
    @DisplayName("업무가 먼저 JUDGED 또는 FAILED로 종결됐으면 발행 콜백이 해당 상태를 보존한다")
    void preservesAlreadyTerminatedBusinessStates() {
        // given
        saveBusinessRecord(55, "JUDGED");
        saveBusinessRecord(56, "FAILED");
        var first = saveEvent(55);
        var second = saveEvent(56);

        // when
        relay.relayBatch();

        // then
        assertThat(businessStatus(55)).isEqualTo("JUDGED");
        assertThat(businessStatus(56)).isEqualTo("FAILED");
        assertThat(outboxStatus(first)).isEqualTo("PUBLISHED");
        assertThat(outboxStatus(second)).isEqualTo("PUBLISHED");
    }

    /**
     * 한 건의 원격 발행 실패가 같은 배치의 다른 정상 발행을 취소하지 않는지 확인한다.
     */
    @Test
    @DisplayName("한 건의 AWS 발행이 실패해도 해당 재시도를 기록하고 나머지 발행을 커밋한다")
    void continuesBatchAfterRemotePublicationFailure() {
        // given
        saveBusinessRecord(55, "ACCEPTED");
        saveBusinessRecord(56, "ACCEPTED");
        var failed = saveEvent(55);
        var succeeded = saveEvent(56);
        willAnswer(invocation -> {
            OutboxEvent event = invocation.getArgument(0);
            if (event.metadata().aggregateId().equals("55")) {
                throw SdkClientException.create("simulated network failure");
            }
            return null;
        }).given(publisher).publish(any(OutboxEvent.class));

        // when
        var attempted = relay.relayBatch();

        // then
        assertThat(attempted).isEqualTo(2);
        assertThat(outboxStatus(failed)).isEqualTo("PENDING");
        assertThat(retryCount(failed)).isEqualTo(1);
        assertThat(businessStatus(55)).isEqualTo("ACCEPTED");
        assertThat(outboxStatus(succeeded)).isEqualTo("PUBLISHED");
        assertThat(businessStatus(56)).isEqualTo("REQUESTED");
    }

    /**
     * 5회까지는 다음 회차 대상에 남고 6회째 실패부터 수동 복구 전까지 제외되는지 확인한다.
     */
    @Test
    @DisplayName("발행 실패 5회까지 PENDING을 유지하고 6회째 FAILED로 전이한 뒤 자동 재시도하지 않는다")
    void stopsAutomaticRetriesAfterSixthFailure() {
        // given
        var metadata = saveEvent(55);
        willThrow(SdkClientException.create("simulated network failure"))
            .given(publisher)
            .publish(any(OutboxEvent.class));

        // when: 5회 실패까지 커밋한 후 상태 경계를 확인한다.
        for (var attempt = 0; attempt < 5; attempt++) {
            relay.relayBatch();
        }

        // then
        assertThat(outboxStatus(metadata)).isEqualTo("PENDING");
        assertThat(retryCount(metadata)).isEqualTo(5);

        // when: 다음 실패를 커밋하고 한 회차 더 실행한다.
        relay.relayBatch();
        var attemptedAfterLimit = relay.relayBatch();

        // then
        assertThat(outboxStatus(metadata)).isEqualTo("FAILED");
        assertThat(retryCount(metadata)).isEqualTo(6);
        assertThat(attemptedAfterLimit).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT published_at FROM outbox_events", LocalDateTime.class)).isNull();
        verify(publisher, times(6)).publish(any(OutboxEvent.class));
    }

    /**
     * 원격 발행 뒤 업무 UPDATE까지 수행하고 실패시킨 후 재기동해 원자성과 같은 ID 재발행을 확인한다.
     */
    @Test
    @DisplayName("발행 후 업무 갱신이 실패하면 함께 롤백하고 재기동 뒤 같은 이벤트 ID와 메시지로 발행한다")
    void republishesSameEventAfterCallbackRollbackAndRestart() {
        // given
        saveBusinessRecord(55, "ACCEPTED");
        var metadata = saveEvent(55);
        failAfterBusinessUpdate.set(true);

        // when & then: 콜백 실패가 성공 상태와 업무 변경을 모두 취소해야 한다.
        assertThatThrownBy(() -> relay.relayBatch())
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("simulated callback failure");
        assertThat(outboxStatus(metadata)).isEqualTo("PENDING");
        assertThat(retryCount(metadata)).isZero();
        assertThat(businessStatus(55)).isEqualTo("ACCEPTED");

        // when: DB를 보존한 채 Bean을 재생성해 새 Relay로 다시 처리한다.
        context.close();
        failAfterBusinessUpdate.set(false);
        openContext();
        relay.relayBatch();

        // then
        assertThat(outboxStatus(metadata)).isEqualTo("PUBLISHED");
        assertThat(businessStatus(55)).isEqualTo("REQUESTED");
        var sent = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(publisher, times(2)).publish(sent.capture());
        assertThat(sent.getAllValues())
            .extracting(event -> event.metadata().eventId())
            .containsExactly(metadata.eventId(), metadata.eventId());
        assertThat(sent.getAllValues().get(0).message()).isEqualTo(sent.getAllValues().get(1).message());
    }

    /**
     * 회차별 상한보다 한 건 많은 이벤트를 저장해 초과분이 다음 회차에서 처리되는지 확인한다.
     */
    @Test
    @DisplayName("한 회차는 최대 100건만 발행하고 남은 이벤트를 다음 회차에 DB에서 다시 선정한다")
    void limitsBatchToOneHundredEvents() {
        // given
        transaction.executeWithoutResult(status -> {
            for (var aggregateId = 1; aggregateId <= 101; aggregateId++) {
                appendEvent(aggregateId);
            }
        });

        // when
        var firstAttempted = relay.relayBatch();

        // then
        assertThat(firstAttempted).isEqualTo(100);
        assertThat(countEvents("PUBLISHED")).isEqualTo(100);
        assertThat(countEvents("PENDING")).isEqualTo(1);

        // when
        var secondAttempted = relay.relayBatch();

        // then
        assertThat(secondAttempted).isEqualTo(1);
        assertThat(countEvents("PUBLISHED")).isEqualTo(101);
        assertThat(countEvents("PENDING")).isZero();
    }

    /**
     * 다른 트랜잭션이 잠근 첫 행을 건너뛰고 다음 행을 발행한 뒤 잠금 해제 후 첫 행을 처리한다.
     *
     * @throws Exception 작업 스레드 동기화 또는 제한 시간 내 종료에 실패할 때
     */
    @Test
    @DisplayName("다른 Relay가 잠근 행은 기다리지 않고 제외하며 해제 후 다음 회차에서 처리한다")
    void skipsRowsLockedByAnotherTransaction() throws Exception {
        // given
        var first = saveEvent(55);
        var second = saveEvent(56);
        var lockedId
            = jdbcTemplate
                .queryForObject(
                    "SELECT id FROM outbox_events WHERE event_id = ?",
                    Long.class,
                    first.eventId().toString()
                );
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var locking = executor.submit(() -> transaction.executeWithoutResult(status -> {
                jdbcTemplate
                    .queryForObject("SELECT id FROM outbox_events WHERE id = ? FOR UPDATE", Long.class, lockedId);
                locked.countDown();
                awaitRelease(release);
            }));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

                // when
                var attempted = relay.relayBatch();

                // then
                assertThat(attempted).isEqualTo(1);
                assertThat(outboxStatus(first)).isEqualTo("PENDING");
                assertThat(outboxStatus(second)).isEqualTo("PUBLISHED");
            } finally {
                release.countDown();
            }
            locking.get(10, TimeUnit.SECONDS);
        }

        // when
        relay.relayBatch();

        // then
        assertThat(outboxStatus(first)).isEqualTo("PUBLISHED");
    }

    /**
     * 재기동해도 완료·실패 행을 자동으로 재발행하지 않는 대상 조건을 확인한다.
     */
    @Test
    @DisplayName("PUBLISHED와 FAILED는 Relay의 자동 발행 대상에서 제외한다")
    void excludesPublishedAndFailedEvents() {
        // given
        var published = saveEvent(55);
        var failed = saveEvent(56);
        jdbcTemplate
            .update("UPDATE outbox_events SET status = 'PUBLISHED' WHERE event_id = ?", published.eventId().toString());
        jdbcTemplate
            .update("UPDATE outbox_events SET status = 'FAILED' WHERE event_id = ?", failed.eventId().toString());

        // when
        var attempted = relay.relayBatch();

        // then
        assertThat(attempted).isZero();
        verifyNoInteractions(publisher);
    }

    /**
     * 저장 기능만 등록한 서비스에서는 예약 작업을 시작하지 않으면서 Relay의 정해진 주기를 확인한다.
     *
     * @throws NoSuchMethodException 검증할 Relay 실행 메서드가 없을 때
     */
    @Test
    @DisplayName("저장 설정만으로 예약 실행을 활성화하지 않고 Relay는 1초 지연과 5초 초기 지연을 선언한다")
    void keepsSchedulingExplicitAndUsesContractDelays() throws NoSuchMethodException {
        // when
        var schedule = OutboxRelay.class.getMethod("relayBatch").getAnnotation(Scheduled.class);

        // then
        assertThat(context.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
        assertThat(schedule.fixedDelay()).isEqualTo(1000);
        assertThat(schedule.initialDelay()).isEqualTo(5000);
    }

    /**
     * 호출자 업무 트랜잭션을 중단한 새 발행 트랜잭션에서 미커밋 이벤트가 보이지 않는지 확인한다.
     */
    @Test
    @DisplayName("업무 트랜잭션이 아직 커밋하지 않은 Outbox는 발행하지 않고 커밋 후 다음 회차에 처리한다")
    void waitsForBusinessCommitBeforePublishing() {
        // when: 업무 트랜잭션 안에서 이벤트를 기록한 뒤 별도 Relay 트랜잭션을 실행한다.
        var metadata = transaction.execute(status -> {
            var pending = appendEvent(55);
            assertThat(relay.relayBatch()).isZero();
            return pending;
        });

        // then
        assertThat(outboxStatus(metadata)).isEqualTo("PENDING");
        verifyNoInteractions(publisher);

        // when: 업무 커밋 뒤 다음 회차에서 같은 행을 처리한다.
        var attemptedAfterCommit = relay.relayBatch();

        // then
        assertThat(attemptedAfterCommit).isEqualTo(1);
        assertThat(outboxStatus(metadata)).isEqualTo("PUBLISHED");
    }

    /**
     * DB를 초기화하지 않고 공통 저장 설정·수동 Relay·고정 Clock을 새 컨텍스트에 연결한다.
     * 재기동 테스트도 같은 경로를 사용해 메모리에 진행 상태가 남지 않도록 한다.
     */
    private void openContext() {
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
        context
            .registerBean(
                OutboxRelay.class,
                () -> new OutboxRelay(
                    context.getBean(JdbcOutboxStore.class),
                    publisher,
                    List.of(this::updateBusinessState),
                    context.getBean(Clock.class)
                )
            );
        context.refresh();
        transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        writer = context.getBean(OutboxEventWriter.class);
        relay = context.getBean(OutboxRelay.class);
    }

    /**
     * 서비스 소유의 접수 상태만 조건부로 갱신하고 필요할 때 갱신 뒤 실패를 발생시킨다.
     *
     * @param event 같은 발행 트랜잭션에서 업무 상태를 연결할 이벤트
     */
    private void updateBusinessState(OutboxEvent event) {
        jdbcTemplate
            .update(
                "UPDATE business_records SET status = 'REQUESTED' WHERE id = ? AND status = 'ACCEPTED'",
                Long.parseLong(event.metadata().aggregateId())
            );
        if (failAfterBusinessUpdate.get()) {
            throw new IllegalStateException("simulated callback failure");
        }
    }

    /**
     * 선점 경합 테스트의 잠금 유지 구간을 제한 시간 내에서 제어한다.
     *
     * @param release 메인 테스트가 발행 확인 후 해제할 신호
     */
    private void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release the test lock");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test lock holder was interrupted", failure);
        }
    }

    /**
     * 트랜잭션 밖에서 호출할 테스트용 이벤트 저장 경로다.
     *
     * @param aggregateId 저장할 이벤트의 업무 대상 ID
     * @return 커밋한 Outbox의 메타데이터
     */
    private EventMetadata saveEvent(long aggregateId) {
        return transaction.execute(status -> appendEvent(aggregateId));
    }

    /**
     * 여러 건을 같은 준비 트랜잭션에 저장할 때 기존 경계에 참여해 Outbox를 기록한다.
     *
     * @param aggregateId 테스트 업무 대상 ID
     * @return 저장한 이벤트의 메타데이터
     */
    private EventMetadata appendEvent(long aggregateId) {
        return writer
            .append(
                "EXAM_SUBMISSION",
                "ExamSubmissionRequested",
                "v1",
                Long.toString(aggregateId),
                "request-test",
                Map.of("examSubmissionId", Long.toString(aggregateId))
            );
    }

    /**
     * 콜백의 조건부 갱신을 확인할 업무 행을 실제 DB에 저장한다.
     *
     * @param id 이벤트의 애그리게이트 ID와 연결할 업무 PK
     * @param status 발행 전 또는 먼저 도착한 결과가 기록한 업무 상태
     */
    private void saveBusinessRecord(long id, String status) {
        jdbcTemplate.update("INSERT INTO business_records (id, status) VALUES (?, ?)", id, status);
    }

    /**
     * 검증할 이벤트 한 건의 커밋된 Outbox 상태를 조회한다.
     *
     * @param metadata 조회할 이벤트의 메타데이터
     * @return DB에 남은 발행 상태
     */
    private String outboxStatus(EventMetadata metadata) {
        return jdbcTemplate
            .queryForObject(
                "SELECT status FROM outbox_events WHERE event_id = ?",
                String.class,
                metadata.eventId().toString()
            );
    }

    /**
     * 이벤트별로 실제 커밋한 원격 발행 실패 횟수를 조회한다.
     *
     * @param metadata 조회할 이벤트의 메타데이터
     * @return DB에 남은 실패 횟수
     */
    private int retryCount(EventMetadata metadata) {
        return jdbcTemplate
            .queryForObject(
                "SELECT retry_count FROM outbox_events WHERE event_id = ?",
                Integer.class,
                metadata.eventId().toString()
            );
    }

    /**
     * 콜백 원자성 검증에 사용할 업무 상태만 조회한다.
     *
     * @param id 조회할 업무 PK
     * @return 커밋된 업무 상태
     */
    private String businessStatus(long id) {
        return jdbcTemplate.queryForObject("SELECT status FROM business_records WHERE id = ?", String.class, id);
    }

    /**
     * 회차 처리량 검증에 사용할 상태별 이벤트 수를 조회한다.
     *
     * @param status 집계할 Outbox 상태
     * @return 해당 상태로 커밋된 행 수
     */
    private int countEvents(String status) {
        return jdbcTemplate
            .queryForObject("SELECT COUNT(*) FROM outbox_events WHERE status = ?", Integer.class, status);
    }
}
