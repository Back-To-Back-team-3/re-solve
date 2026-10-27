package com.backtoback.notification.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.notification.delivery.repository.EmitterRepository;
import com.backtoback.notification.delivery.service.NotificationDeliveryService;
import com.backtoback.notification.event.repository.ProcessedEventRepository;
import com.backtoback.notification.event.service.NotificationEventProcessor;
import com.backtoback.notification.event.service.ProcessedEventCleanupService;
import com.backtoback.notification.global.error.NotificationException;
import com.backtoback.notification.notification.repository.NotificationRepository;
import com.backtoback.notification.notification.repository.NotificationWithdrawnMemberRepository;
import com.backtoback.notification.preference.domain.NotificationPreference;
import com.backtoback.notification.preference.repository.NotificationPreferenceRepository;
import com.backtoback.notification.reminder.domain.ReminderStatus;
import com.backtoback.notification.reminder.repository.NotificationReminderRepository;
import com.backtoback.notification.reminder.service.ExamReminderService;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class NotificationRetentionAndWithdrawalIntegrationTest {

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
    private NotificationRetentionService retentionService;

    @Autowired
    private ProcessedEventCleanupService processedEventCleanupService;

    @Autowired
    private NotificationEventProcessor eventProcessor;

    @Autowired
    private NotificationDeliveryService deliveryService;

    @Autowired
    private EmitterRepository emitterRepository;

    @Autowired
    private ExamReminderService examReminderService;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationWithdrawnMemberRepository withdrawnMemberRepository;

    @Autowired
    private NotificationPreferenceRepository preferenceRepository;

    @Autowired
    private NotificationReminderRepository reminderRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Clock clock;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAllInBatch();
        withdrawnMemberRepository.deleteAllInBatch();
        preferenceRepository.deleteAllInBatch();
        reminderRepository.deleteAllInBatch();
        processedEventRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("7.2·0.2.3 만료 데이터 정리 시 90일 알림·7일 실제 참가 예약·14일 음수 표식 및 14일 소비 이벤트를 기준에 따라 삭제한다")
    void retentionCleanupDeletesOnlyExpiredRowsAndCleansUpExpiredSentinelsAfter14Days() {
        // given
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        LocalDateTime expiredNotificationTime = now.minusDays(91);
        LocalDateTime activeNotificationTime = now.minusDays(10);
        LocalDateTime expiredReminderTime = now.minusDays(8);
        LocalDateTime expiredEventTime = now.minusDays(15);
        LocalDateTime activeEventTime = now.minusDays(3);

        // 1) 알림: 91일 경과 1건, 10일 경과 1건
        jdbcTemplate
            .update(
                "INSERT INTO notifications (member_id, source_event_id, type, title, content, is_read, "
                    + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                7L,
                "evt-old-noti",
                "SUBMISSION_JUDGED",
                "제목",
                "본문",
                false,
                expiredNotificationTime,
                expiredNotificationTime
            );
        jdbcTemplate
            .update(
                "INSERT INTO notifications (member_id, source_event_id, type, title, content, is_read, "
                    + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                7L,
                "evt-recent-noti",
                "SUBMISSION_JUDGED",
                "제목",
                "본문",
                false,
                activeNotificationTime,
                activeNotificationTime
            );

        // 2) 예약: 8일 경과 실제 참가자 종결 행(411) + 8일 경과 음수 표식 행(-109, 보존 대상) + 15일 경과 음수 표식 행(-111, 삭제 대상)
        jdbcTemplate
            .update(
                "INSERT INTO notification_reminders (member_id, exam_id, participant_id, fire_at, starts_at, "
                    + "context_revision, source_event_id, status, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                7L,
                55L,
                411L,
                expiredReminderTime,
                expiredReminderTime,
                1,
                "evt-rem-1",
                ReminderStatus.SENT.name(),
                expiredReminderTime,
                expiredReminderTime
            );
        jdbcTemplate
            .update(
                "INSERT INTO notification_reminders (member_id, exam_id, participant_id, fire_at, starts_at, "
                    + "context_revision, source_event_id, status, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                0L,
                55L,
                -109L,
                expiredReminderTime,
                expiredReminderTime,
                2,
                "evt-exam-cancel-recent",
                ReminderStatus.CANCELED.name(),
                expiredReminderTime,
                expiredReminderTime
            );
        jdbcTemplate
            .update(
                "INSERT INTO notification_reminders (member_id, exam_id, participant_id, fire_at, starts_at, "
                    + "context_revision, source_event_id, status, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                0L,
                56L,
                -111L,
                expiredEventTime,
                expiredEventTime,
                2,
                "evt-exam-cancel-expired",
                ReminderStatus.CANCELED.name(),
                expiredEventTime,
                expiredEventTime
            );

        // 3) 소비 이벤트: 15일 경과 1건, 3일 경과 1건
        jdbcTemplate
            .update(
                "INSERT INTO processed_events (event_id, event_type, processed_at, created_at) VALUES (?, ?, ?, ?)",
                "evt-old-proc",
                "SubmissionJudged",
                expiredEventTime,
                expiredEventTime
            );
        jdbcTemplate
            .update(
                "INSERT INTO processed_events (event_id, event_type, processed_at, created_at) VALUES (?, ?, ?, ?)",
                "evt-recent-proc",
                "SubmissionJudged",
                activeEventTime,
                activeEventTime
            );

        // when
        int deletedNotifications = retentionService.deleteExpiredNotificationsBatch(1000);
        int deletedReminders = retentionService.deleteExpiredRemindersBatch(1000);
        int deletedSentinels = retentionService.deleteExpiredSentinelRemindersBatch(1000);
        int deletedEvents = processedEventCleanupService.deleteExpiredProcessedEventsBatch(1000);

        // then
        assertThat(deletedNotifications).isEqualTo(1);
        assertThat(notificationRepository.count()).isEqualTo(1);

        assertThat(deletedReminders).isEqualTo(1);
        assertThat(reminderRepository.findByParticipantId(411L)).isEmpty();
        assertThat(reminderRepository.findByParticipantId(-109L)).isPresent();

        assertThat(deletedSentinels).isEqualTo(1);
        assertThat(reminderRepository.findByParticipantId(-111L)).isEmpty();

        assertThat(deletedEvents).isEqualTo(1);
        assertThat(processedEventRepository.existsByEventId("evt-old-proc")).isFalse();
        assertThat(processedEventRepository.existsByEventId("evt-recent-proc")).isTrue();
    }

    @Test
    @DisplayName("4.1 M3·6 MemberWithdrawn 이벤트 소비 시 회원 데이터 삭제·SSE 연결 종료·재연결 차단 및 이후 지연 이벤트 수신 제외가 동작한다")
    void memberWithdrawnDeletesMemberDataClosesSseRejectsResubscribeAndExcludesFutureNotifications() {
        // given
        Long memberId = 7L;
        preferenceRepository.saveAndFlush(NotificationPreference.createDefault(memberId));
        examReminderService
            .registerReminder("evt-reg-1", memberId, 55L, 411L, 1, LocalDateTime.now(clock).plusHours(2));
        deliveryService.subscribe(memberId, null);

        eventProcessor.process("evt-judged-before", "SubmissionJudged", objectMapper.readTree("""
            {
              "eventId": "evt-judged-before",
              "eventType": "SubmissionJudged",
              "submissionId": "9001",
              "userId": "7",
              "problemId": "1001",
              "verdict": "AC"
            }
            """));

        assertThat(notificationRepository.findByMemberIdAndIsReadFalse(memberId)).hasSize(1);
        assertThat(preferenceRepository.existsByMemberId(memberId)).isTrue();
        assertThat(reminderRepository.findByParticipantId(411L)).isPresent();
        assertThat(emitterRepository.findAllByMemberId(memberId)).hasSize(1);

        // when: MemberWithdrawn 소비
        eventProcessor.process("evt-withdraw-7", "MemberWithdrawn", objectMapper.readTree("""
            {
              "eventId": "evt-withdraw-7",
              "eventType": "MemberWithdrawn",
              "schemaVersion": "v1",
              "occurredAt": "2026-10-21T10:00:00.000000+09:00",
              "aggregateId": "7",
              "correlationId": "req-3f81",
              "memberId": "7",
              "withdrawnAt": "2026-10-21T10:00:00.000000+09:00",
              "sourceVersion": 12
            }
            """));

        // then: 알림, 수신 설정, 실제 참가 예약 물리 삭제 및 탈퇴 회원 기록·SSE 연결 종료·재구독 403 거절 확인
        assertThat(notificationRepository.findByMemberIdAndIsReadFalse(memberId)).isEmpty();
        assertThat(preferenceRepository.existsByMemberId(memberId)).isFalse();
        assertThat(reminderRepository.findByParticipantId(411L)).isEmpty();
        assertThat(withdrawnMemberRepository.existsByMemberId(memberId)).isTrue();
        assertThat(emitterRepository.findAllByMemberId(memberId)).isEmpty();
        assertThatThrownBy(() -> deliveryService.subscribe(memberId, null))
            .isInstanceOf(NotificationException.class)
            .extracting(ex -> ((NotificationException)ex).getErrorCode())
            .isEqualTo(CommonErrorCode.AUTH_ACCESS_DENIED);

        // when: 탈퇴 이후 지연 도착한 이벤트 수신
        eventProcessor.process("evt-judged-after-withdraw", "SubmissionJudged", objectMapper.readTree("""
            {
              "eventId": "evt-judged-after-withdraw",
              "eventType": "SubmissionJudged",
              "submissionId": "9002",
              "userId": "7",
              "problemId": "1002",
              "verdict": "AC"
            }
            """));

        // then: 탈퇴 회원 앞으로 새 알림이 생성되지 않음
        assertThat(notificationRepository.findByMemberIdAndIsReadFalse(memberId)).isEmpty();
    }
}
