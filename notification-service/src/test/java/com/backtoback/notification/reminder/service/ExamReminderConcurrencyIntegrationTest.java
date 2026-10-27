package com.backtoback.notification.reminder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.notification.reminder.domain.NotificationReminder;
import com.backtoback.notification.reminder.domain.ReminderStatus;
import com.backtoback.notification.reminder.repository.NotificationReminderRepository;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ExamReminderConcurrencyIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("notification_db");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.cloud.aws.sqs.enabled", () -> "false");
    }

    @Autowired
    private ExamReminderService examReminderService;

    @Autowired
    private NotificationReminderRepository reminderRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private Clock clock;

    @BeforeEach
    void setUp() {
        reminderRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("7.1 ON DUPLICATE KEY UPDATE id = id 쿼리는 중복 participant_id만 0건으로 무시하고 NOT NULL 위반은 예외를 던진다")
    void insertIgnoreReminderIgnoresOnlyDuplicateKeyAndRejectsNullViolation() {
        // given
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        LocalDateTime startsAt = now.plusMinutes(30);

        // when: 최초 삽입 시 1건, 동일 participant_id 재삽입 시 0건 반환
        int firstInsert
            = transactionTemplate
                .execute(
                    status -> reminderRepository
                        .insertIgnoreReminder(
                            7L,
                            55L,
                            411L,
                            startsAt.minusMinutes(5),
                            startsAt,
                            1,
                            "evt-1",
                            ReminderStatus.SCHEDULED.name(),
                            now
                        )
                );
        int duplicateInsert
            = transactionTemplate
                .execute(
                    status -> reminderRepository
                        .insertIgnoreReminder(
                            7L,
                            55L,
                            411L,
                            startsAt.minusMinutes(5),
                            startsAt,
                            1,
                            "evt-2",
                            ReminderStatus.SCHEDULED.name(),
                            now
                        )
                );

        // then
        assertThat(firstInsert).isEqualTo(1);
        assertThat(duplicateInsert).isEqualTo(0);

        assertThatThrownBy(
            () -> transactionTemplate
                .execute(
                    status -> reminderRepository
                        .insertIgnoreReminder(
                            null,
                            55L,
                            412L,
                            startsAt.minusMinutes(5),
                            startsAt,
                            1,
                            "evt-null",
                            ReminderStatus.SCHEDULED.name(),
                            now
                        )
                )
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("7.1 발화 대상 조회 후 ExamUpdated로 fire_at이 미래로 미뤄지면 markAsSentIfScheduled가 0건을 반환하고 SCHEDULED를 유지한다")
    void markAsSentIfScheduledRejectsPostponedReminder() {
        // given: 현재 시각 기준으로 발화 시각이 도래한 예약 등록
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        LocalDateTime imminentStartsAt = now.plusMinutes(3);
        examReminderService.registerReminder("evt-reg-1", 7L, 55L, 411L, 1, imminentStartsAt);

        NotificationReminder saved = reminderRepository.findByParticipantId(411L).orElseThrow();

        // when: 발화 직전 ExamUpdated로 시작 시각이 30분 뒤로 연기됨
        examReminderService.rescheduleByExamUpdated("evt-upd-2", 55L, 2, now.plusMinutes(30));
        boolean dispatched = examReminderService.dispatchSingleReminder(saved);

        // then: 조기 발화되지 않고 새 시각의 SCHEDULED 상태로 유지된다
        assertThat(dispatched).isFalse();
        NotificationReminder reloaded = reminderRepository.findByParticipantId(411L).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ReminderStatus.SCHEDULED);
        assertThat(reloaded.getContextRevision()).isEqualTo(2);
    }

    @Test
    @DisplayName("7.1 REPEATABLE READ 트랜잭션이 이미 시작된 뒤 다른 트랜잭션이 ExamCanceled를 커밋해도 FOR SHARE 현재 읽기로 감지하여 예약을 취소한다")
    void registerReminderDetectsConcurrentExamCanceledUnderRepeatableRead() throws Exception {
        // given
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        LocalDateTime startsAt = now.plusMinutes(30);
        Long examId = 77L;
        Long participantId = 701L;

        CountDownLatch snapshotEstablishedLatch = new CountDownLatch(1);
        CountDownLatch examCanceledCommittedLatch = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // Tx 1: REPEATABLE READ 트랜잭션을 먼저 열어 일반 SELECT로 초기 스냅샷을 고정한 뒤,
            // Tx 2가 ExamCanceled를 커밋한 이후 registerReminder를 호출한다.
            Future<?> registerFuture = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                reminderRepository.findByParticipantId(participantId);
                snapshotEstablishedLatch.countDown();
                try {
                    assertThat(examCanceledCommittedLatch.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                examReminderService.registerReminder("evt-reg-concurrent", 7L, examId, participantId, 1, startsAt);
            }));

            // Tx 2: Tx 1의 REPEATABLE READ 스냅샷 생성 이후 ExamCanceled를 실행하고 커밋한다.
            Future<?> cancelFuture = executor.submit(() -> {
                try {
                    assertThat(snapshotEstablishedLatch.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                examReminderService.cancelByExamCanceled("evt-exam-cancel-77", examId, 2, now);
                examCanceledCommittedLatch.countDown();
            });

            cancelFuture.get(10, TimeUnit.SECONDS);
            registerFuture.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        // then: 일반 SELECT였다면 초기 스냅샷 때문에 표식을 보지 못해 SCHEDULED가 남았겠지만,
        // FOR SHARE 현재 읽기 덕분에 CANCELED 상태로 저장된다.
        NotificationReminder result = reminderRepository.findByParticipantId(participantId).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(ReminderStatus.CANCELED);
    }
}
