package com.backtoback.notification.reminder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.notification.event.dto.NotificationCreatedEvent;
import com.backtoback.notification.notification.domain.Notification;
import com.backtoback.notification.notification.domain.NotificationType;
import com.backtoback.notification.notification.repository.NotificationRepository;
import com.backtoback.notification.preference.domain.NotificationPreference;
import com.backtoback.notification.preference.repository.NotificationPreferenceRepository;
import com.backtoback.notification.reminder.domain.NotificationReminder;
import com.backtoback.notification.reminder.domain.ReminderStatus;
import com.backtoback.notification.reminder.repository.NotificationReminderRepository;

class ExamReminderServiceTest {

    // 2026-10-20 20:00:00 KST (11:00:00Z)
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-20T11:00:00Z"), ClockConfiguration.SERVICE_ZONE);
    private final LocalDateTime fixedNow = LocalDateTime.of(2026, 10, 20, 20, 0, 0);

    private NotificationReminderRepository reminderRepository;
    private NotificationRepository notificationRepository;
    private NotificationPreferenceRepository preferenceRepository;
    private ApplicationEventPublisher eventPublisher;
    private ExamReminderService examReminderService;

    @BeforeEach
    void setUp() {
        reminderRepository = mock(NotificationReminderRepository.class);
        notificationRepository = mock(NotificationRepository.class);
        preferenceRepository = mock(NotificationPreferenceRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        examReminderService
            = new ExamReminderService(
                reminderRepository,
                notificationRepository,
                preferenceRepository,
                eventPublisher,
                clock
            );
    }

    @Test
    @DisplayName("7.1 시험 참가 등록 시 시작 시각 5분 전을 발화 시각으로 하여 SCHEDULED 예약을 저장한다")
    void registerReminderCreatesScheduledReminderFiveMinutesBeforeStart() {
        // given
        LocalDateTime startsAt = LocalDateTime.of(2026, 10, 20, 21, 0, 0);
        given(reminderRepository.findByParticipantId(411L)).willReturn(Optional.empty());

        // when
        examReminderService.registerReminder("evt-reg-1", 7L, 55L, 411L, 3, startsAt);

        // then
        ArgumentCaptor<NotificationReminder> captor = ArgumentCaptor.forClass(NotificationReminder.class);
        verify(reminderRepository).saveAndFlush(captor.capture());

        NotificationReminder saved = captor.getValue();
        assertThat(saved.getMemberId()).isEqualTo(7L);
        assertThat(saved.getExamId()).isEqualTo(55L);
        assertThat(saved.getParticipantId()).isEqualTo(411L);
        assertThat(saved.getStartsAt()).isEqualTo(startsAt);
        assertThat(saved.getFireAt()).isEqualTo(LocalDateTime.of(2026, 10, 20, 20, 55, 0));
        assertThat(saved.getContextRevision()).isEqualTo(3);
        assertThat(saved.getSourceEventId()).isEqualTo("evt-reg-1");
        assertThat(saved.getStatus()).isEqualTo(ReminderStatus.SCHEDULED);
    }

    @Test
    @DisplayName("7.1 이미 취소 표식(CANCELED)이 존재하는 participantId의 늦은 등록 이벤트는 무시한다")
    void registerReminderWhenCanceledTombstoneExistsIgnoresRegistration() {
        // given
        NotificationReminder tombstone
            = NotificationReminder
                .createCanceledTombstone(7L, 55L, 411L, LocalDateTime.of(2026, 10, 20, 19, 30, 0), 3, "evt-cancel-1");
        given(reminderRepository.findByParticipantId(411L)).willReturn(Optional.of(tombstone));

        // when
        examReminderService.registerReminder("evt-reg-1", 7L, 55L, 411L, 3, LocalDateTime.of(2026, 10, 20, 21, 0, 0));

        // then
        verify(reminderRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("7.1 ExamUpdated 수신 시 SCHEDULED 상태이고 버전이 낮은 예약만 조건부 UPDATE로 갱신한다")
    void rescheduleByExamUpdatedExecutesConditionalUpdate() {
        // given
        LocalDateTime updatedStartsAt = LocalDateTime.of(2026, 10, 20, 21, 30, 0);

        // when
        examReminderService.rescheduleByExamUpdated("evt-upd-4", 55L, 4, updatedStartsAt);

        // then
        verify(reminderRepository)
            .rescheduleScheduledByExamId(
                eq(55L),
                eq(4),
                eq(updatedStartsAt),
                eq(LocalDateTime.of(2026, 10, 20, 21, 25, 0)),
                eq("evt-upd-4"),
                eq(fixedNow)
            );
    }

    @Test
    @DisplayName("7.1 참가 취소 이벤트가 등록보다 먼저 도착하면 CANCELED 취소 표식 행을 생성한다")
    void cancelByParticipantCanceledWhenNotFoundCreatesTombstone() {
        // given
        given(reminderRepository.cancelScheduledByParticipantId(411L, fixedNow)).willReturn(0);
        given(reminderRepository.findByParticipantId(411L)).willReturn(Optional.empty());
        LocalDateTime canceledAt = LocalDateTime.of(2026, 10, 20, 19, 50, 0);

        // when
        examReminderService.cancelByParticipantCanceled("evt-cancel-1", 7L, 55L, 411L, 3, canceledAt);

        // then
        ArgumentCaptor<NotificationReminder> captor = ArgumentCaptor.forClass(NotificationReminder.class);
        verify(reminderRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ReminderStatus.CANCELED);
        assertThat(captor.getValue().getParticipantId()).isEqualTo(411L);
    }

    @Test
    @DisplayName("7.1 취소 표식 생성 중 동시 등록으로 고유 제약 충돌이 발생하면 조건부 취소 UPDATE를 재실행한다")
    void cancelByParticipantCanceledWhenConcurrentInsertCollidesRetriesConditionalCancel() {
        // given
        given(reminderRepository.cancelScheduledByParticipantId(411L, fixedNow)).willReturn(0).willReturn(1);
        given(reminderRepository.findByParticipantId(411L)).willReturn(Optional.empty());
        given(reminderRepository.saveAndFlush(any(NotificationReminder.class)))
            .willThrow(new DataIntegrityViolationException("uk_notification_reminders_participant_id"));

        // when
        examReminderService
            .cancelByParticipantCanceled("evt-cancel-1", 7L, 55L, 411L, 3, LocalDateTime.of(2026, 10, 20, 19, 50, 0));

        // then
        verify(reminderRepository, times(2)).cancelScheduledByParticipantId(411L, fixedNow);
    }

    @Test
    @DisplayName("7.1 예약 발화 시 조건부 SENT 전이에 성공하고 수신 설정이 켜져 있으면 EXAM_STARTING_SOON 알림을 생성한다")
    void dispatchSingleReminderWhenEnabledCreatesNotificationAndPublishesEvent() {
        // given
        NotificationReminder reminder
            = NotificationReminder
                .createScheduled(7L, 55L, 411L, LocalDateTime.of(2026, 10, 20, 20, 3, 0), 3, "evt-reg-1");
        ReflectionTestUtils.setField(reminder, "id", 10L);

        given(reminderRepository.markAsSentIfScheduled(eq(10L), eq(fixedNow))).willReturn(1);
        given(preferenceRepository.findByMemberId(7L)).willReturn(Optional.empty());
        given(
            notificationRepository
                .existsBySourceEventIdAndMemberIdAndType("evt-reg-1", 7L, NotificationType.EXAM_STARTING_SOON)
        ).willReturn(false);
        given(notificationRepository.saveAndFlush(any(Notification.class))).willAnswer(invocation -> {
            Notification saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 900L);
            return saved;
        });

        // when
        boolean dispatched = examReminderService.dispatchSingleReminder(reminder);

        // then
        assertThat(dispatched).isTrue();
        ArgumentCaptor<Notification> notificationCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).saveAndFlush(notificationCaptor.capture());

        Notification saved = notificationCaptor.getValue();
        assertThat(saved.getMemberId()).isEqualTo(7L);
        assertThat(saved.getSourceEventId()).isEqualTo("evt-reg-1");
        assertThat(saved.getType()).isEqualTo(NotificationType.EXAM_STARTING_SOON);
        assertThat(saved.getLinkUrl()).isEqualTo("/exams/55");

        verify(eventPublisher).publishEvent(any(NotificationCreatedEvent.class));
    }

    @Test
    @DisplayName("7.1 예약 발화 시점에 회원의 시험·대회 수신 설정이 꺼져 있으면 SENT 전이만 하고 알림은 생성하지 않는다")
    void dispatchSingleReminderWhenPreferenceDisabledMarksSentOnly() {
        // given
        NotificationReminder reminder
            = NotificationReminder
                .createScheduled(7L, 55L, 411L, LocalDateTime.of(2026, 10, 20, 20, 3, 0), 3, "evt-reg-1");
        ReflectionTestUtils.setField(reminder, "id", 10L);

        NotificationPreference disabledPref = new NotificationPreference(7L, false, true, true, true, true, true);

        given(reminderRepository.markAsSentIfScheduled(eq(10L), eq(fixedNow))).willReturn(1);
        given(preferenceRepository.findByMemberId(7L)).willReturn(Optional.of(disabledPref));

        // when
        boolean dispatched = examReminderService.dispatchSingleReminder(reminder);

        // then
        assertThat(dispatched).isFalse();
        verify(notificationRepository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publishEvent(any());
    }
}
