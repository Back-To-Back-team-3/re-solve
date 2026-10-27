package com.backtoback.notification.reminder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import org.springframework.test.util.ReflectionTestUtils;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.notification.event.dto.NotificationCreatedEvent;
import com.backtoback.notification.notification.domain.Notification;
import com.backtoback.notification.notification.domain.NotificationType;
import com.backtoback.notification.notification.domain.NotificationWithdrawnMember;
import com.backtoback.notification.notification.repository.NotificationRepository;
import com.backtoback.notification.notification.repository.NotificationWithdrawnMemberRepository;
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
    private NotificationWithdrawnMemberRepository withdrawnMemberRepository;
    private ApplicationEventPublisher eventPublisher;
    private ExamReminderService examReminderService;

    @BeforeEach
    void setUp() {
        reminderRepository = mock(NotificationReminderRepository.class);
        notificationRepository = mock(NotificationRepository.class);
        preferenceRepository = mock(NotificationPreferenceRepository.class);
        withdrawnMemberRepository = mock(NotificationWithdrawnMemberRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        examReminderService
            = new ExamReminderService(
                reminderRepository,
                notificationRepository,
                preferenceRepository,
                withdrawnMemberRepository,
                eventPublisher,
                clock
            );
    }

    @Test
    @DisplayName("7.1 시험 참가 등록 시 시작 시각 5분 전을 발화 시각으로 하여 INSERT IGNORE로 SCHEDULED 예약을 저장한다")
    void registerReminderCreatesScheduledReminderFiveMinutesBeforeStart() {
        // given
        LocalDateTime startsAt = LocalDateTime.of(2026, 10, 20, 21, 0, 0);
        given(
            reminderRepository
                .insertIgnoreReminder(
                    7L,
                    55L,
                    411L,
                    LocalDateTime.of(2026, 10, 20, 20, 55, 0),
                    startsAt,
                    3,
                    "evt-reg-1",
                    ReminderStatus.SCHEDULED.name(),
                    fixedNow
                )
        ).willReturn(1);

        // when
        examReminderService.registerReminder("evt-reg-1", 7L, 55L, 411L, 3, startsAt);

        // then
        verify(reminderRepository)
            .insertIgnoreReminder(
                eq(7L),
                eq(55L),
                eq(411L),
                eq(LocalDateTime.of(2026, 10, 20, 20, 55, 0)),
                eq(startsAt),
                eq(3),
                eq("evt-reg-1"),
                eq(ReminderStatus.SCHEDULED.name()),
                eq(fixedNow)
            );
    }

    @Test
    @DisplayName("7.1 이미 취소 표식(CANCELED)이나 중복 등록이 존재하는 participantId는 INSERT IGNORE가 0건을 반환하며 예외 없이 무시된다")
    void registerReminderWhenDuplicateOrTombstoneExistsIgnoresWithoutException() {
        // given
        LocalDateTime startsAt = LocalDateTime.of(2026, 10, 20, 21, 0, 0);
        given(
            reminderRepository
                .insertIgnoreReminder(
                    7L,
                    55L,
                    411L,
                    LocalDateTime.of(2026, 10, 20, 20, 55, 0),
                    startsAt,
                    3,
                    "evt-reg-1",
                    ReminderStatus.SCHEDULED.name(),
                    fixedNow
                )
        ).willReturn(0);

        // when & then (예외 및 트랜잭션 rollback-only 오염 없이 정상 종료)
        examReminderService.registerReminder("evt-reg-1", 7L, 55L, 411L, 3, startsAt);

        verify(reminderRepository)
            .insertIgnoreReminder(
                eq(7L),
                eq(55L),
                eq(411L),
                eq(LocalDateTime.of(2026, 10, 20, 20, 55, 0)),
                eq(startsAt),
                eq(3),
                eq("evt-reg-1"),
                eq(ReminderStatus.SCHEDULED.name()),
                eq(fixedNow)
            );
    }

    @Test
    @DisplayName("7.1 ExamUpdated가 먼저 처리되어 더 높은 버전의 시험 스케줄 스냅샷이 있으면 지연 도착한 참가 등록에 최신 스케줄을 적용한다")
    void registerReminderWhenNewerExamSnapshotExistsAppliesSnapshotSchedule() {
        // given: 등록 이벤트는 이전 시각(이미 지남: 19:50, rev=1)이지만, 선행 ExamUpdated 스냅샷(21:30, rev=4)이 저장된 상태
        LocalDateTime staleStartsAt = LocalDateTime.of(2026, 10, 20, 19, 50, 0);
        LocalDateTime updatedStartsAt = LocalDateTime.of(2026, 10, 20, 21, 30, 0);
        NotificationReminder snapshot
            = NotificationReminder
                .builder()
                .memberId(0L)
                .examId(55L)
                .participantId(ExamReminderService.examScheduleSnapshotParticipantId(55L))
                .fireAt(updatedStartsAt.minusMinutes(5))
                .startsAt(updatedStartsAt)
                .contextRevision(4)
                .sourceEventId("evt-upd-4")
                .status(ReminderStatus.CANCELED)
                .build();

        given(
            reminderRepository.findByParticipantIdForShare(ExamReminderService.examCanceledTombstoneParticipantId(55L))
        ).willReturn(Optional.empty());
        given(
            reminderRepository.findByParticipantIdForShare(ExamReminderService.examScheduleSnapshotParticipantId(55L))
        ).willReturn(Optional.of(snapshot));
        given(
            reminderRepository
                .insertIgnoreReminder(
                    7L,
                    55L,
                    411L,
                    LocalDateTime.of(2026, 10, 20, 21, 25, 0),
                    updatedStartsAt,
                    4,
                    "evt-upd-4",
                    ReminderStatus.SCHEDULED.name(),
                    fixedNow
                )
        ).willReturn(1);

        // when
        examReminderService.registerReminder("evt-reg-stale", 7L, 55L, 411L, 1, staleStartsAt);

        // then
        verify(reminderRepository)
            .insertIgnoreReminder(
                eq(7L),
                eq(55L),
                eq(411L),
                eq(LocalDateTime.of(2026, 10, 20, 21, 25, 0)),
                eq(updatedStartsAt),
                eq(4),
                eq("evt-upd-4"),
                eq(ReminderStatus.SCHEDULED.name()),
                eq(fixedNow)
            );
    }

    @Test
    @DisplayName("7.1 ExamCanceled가 먼저 처리되어 시험 취소 종단 표식이 존재하면 지연 도착한 참가 등록을 CANCELED 상태로 차단한다")
    void registerReminderWhenExamAlreadyCanceledInsertsCanceledTombstone() {
        // given
        LocalDateTime startsAt = LocalDateTime.of(2026, 10, 20, 21, 0, 0);
        NotificationReminder canceledSentinel
            = NotificationReminder
                .createCanceledTombstone(
                    0L,
                    55L,
                    ExamReminderService.examCanceledTombstoneParticipantId(55L),
                    fixedNow,
                    4,
                    "evt-exam-cancel-1"
                );
        given(
            reminderRepository.findByParticipantIdForShare(ExamReminderService.examCanceledTombstoneParticipantId(55L))
        ).willReturn(Optional.of(canceledSentinel));

        // when
        examReminderService.registerReminder("evt-reg-late", 7L, 55L, 411L, 3, startsAt);

        // then
        verify(reminderRepository)
            .insertIgnoreReminder(
                eq(7L),
                eq(55L),
                eq(411L),
                eq(LocalDateTime.of(2026, 10, 20, 20, 55, 0)),
                eq(startsAt),
                eq(3),
                eq("evt-reg-late"),
                eq(ReminderStatus.CANCELED.name()),
                eq(fixedNow)
            );
    }

    @Test
    @DisplayName("7.1 참가 등록 INSERT 직전 동시 트랜잭션에서 ExamCanceled가 커밋되면 FOR SHARE 재확인으로 즉시 취소한다")
    void registerReminderWhenExamCanceledConcurrentlyDuringInsertCancelsReminder() {
        // given
        LocalDateTime startsAt = LocalDateTime.of(2026, 10, 20, 21, 0, 0);
        Long canceledSentinelId = ExamReminderService.examCanceledTombstoneParticipantId(55L);
        NotificationReminder canceledSentinel
            = NotificationReminder.createCanceledTombstone(0L, 55L, canceledSentinelId, fixedNow, 4, "evt-cancel-55");

        given(reminderRepository.findByParticipantIdForShare(canceledSentinelId))
            .willReturn(Optional.empty())
            .willReturn(Optional.of(canceledSentinel));
        given(
            reminderRepository.findByParticipantIdForShare(ExamReminderService.examScheduleSnapshotParticipantId(55L))
        ).willReturn(Optional.empty());
        given(
            reminderRepository
                .insertIgnoreReminder(
                    7L,
                    55L,
                    411L,
                    LocalDateTime.of(2026, 10, 20, 20, 55, 0),
                    startsAt,
                    3,
                    "evt-reg-1",
                    ReminderStatus.SCHEDULED.name(),
                    fixedNow
                )
        ).willReturn(1);

        // when
        examReminderService.registerReminder("evt-reg-1", 7L, 55L, 411L, 3, startsAt);

        // then
        verify(reminderRepository).cancelScheduledByParticipantId(411L, fixedNow);
    }

    @Test
    @DisplayName("7.1 ExamUpdated 수신 시 시험 스케줄 스냅샷을 갱신하고 SCHEDULED 상태이고 버전이 낮은 예약만 조건부 UPDATE로 갱신한다")
    void rescheduleByExamUpdatedExecutesConditionalUpdate() {
        // given
        LocalDateTime updatedStartsAt = LocalDateTime.of(2026, 10, 20, 21, 30, 0);
        Long snapshotParticipantId = ExamReminderService.examScheduleSnapshotParticipantId(55L);
        given(
            reminderRepository
                .insertIgnoreReminder(
                    0L,
                    55L,
                    snapshotParticipantId,
                    LocalDateTime.of(2026, 10, 20, 21, 25, 0),
                    updatedStartsAt,
                    4,
                    "evt-upd-4",
                    ReminderStatus.CANCELED.name(),
                    fixedNow
                )
        ).willReturn(0);

        // when
        examReminderService.rescheduleByExamUpdated("evt-upd-4", 55L, 4, updatedStartsAt);

        // then
        verify(reminderRepository)
            .updateExamSnapshotIfNewer(
                eq(snapshotParticipantId),
                eq(4),
                eq(updatedStartsAt),
                eq(LocalDateTime.of(2026, 10, 20, 21, 25, 0)),
                eq("evt-upd-4"),
                eq(fixedNow)
            );
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
    @DisplayName("7.1 ExamCanceled 수신 시 실제 이벤트 메타데이터로 시험 취소 종단 표식을 기록하고 발화 대기 예약을 일괄 취소한다")
    void cancelByExamCanceledInsertsTombstoneAndCancelsAllScheduled() {
        // given
        LocalDateTime canceledAt = LocalDateTime.of(2026, 10, 20, 20, 15, 0);

        // when
        examReminderService.cancelByExamCanceled("evt-exam-cancel-1", 55L, 4, canceledAt);

        // then
        verify(reminderRepository)
            .insertIgnoreReminder(
                eq(0L),
                eq(55L),
                eq(ExamReminderService.examCanceledTombstoneParticipantId(55L)),
                eq(canceledAt),
                eq(canceledAt),
                eq(4),
                eq("evt-exam-cancel-1"),
                eq(ReminderStatus.CANCELED.name()),
                eq(fixedNow)
            );
        verify(reminderRepository).cancelAllScheduledByExamId(55L, fixedNow);
    }

    @Test
    @DisplayName("7.1 참가 취소 이벤트가 등록보다 먼저 도착하면 INSERT IGNORE로 CANCELED 취소 표식 행을 생성한다")
    void cancelByParticipantCanceledWhenNotFoundCreatesTombstone() {
        // given
        given(reminderRepository.cancelScheduledByParticipantId(411L, fixedNow)).willReturn(0);
        LocalDateTime canceledAt = LocalDateTime.of(2026, 10, 20, 19, 50, 0);
        given(
            reminderRepository
                .insertIgnoreReminder(
                    7L,
                    55L,
                    411L,
                    canceledAt,
                    canceledAt,
                    3,
                    "evt-cancel-1",
                    ReminderStatus.CANCELED.name(),
                    fixedNow
                )
        ).willReturn(1);

        // when
        examReminderService.cancelByParticipantCanceled("evt-cancel-1", 7L, 55L, 411L, 3, canceledAt);

        // then
        verify(reminderRepository, times(1)).cancelScheduledByParticipantId(411L, fixedNow);
        verify(reminderRepository)
            .insertIgnoreReminder(
                eq(7L),
                eq(55L),
                eq(411L),
                eq(canceledAt),
                eq(canceledAt),
                eq(3),
                eq("evt-cancel-1"),
                eq(ReminderStatus.CANCELED.name()),
                eq(fixedNow)
            );
    }

    @Test
    @DisplayName("7.1 취소 표식 INSERT IGNORE 시점에 동시 등록이 먼저 커밋되어 0건이 반환되면 조건부 취소 UPDATE를 재실행한다")
    void cancelByParticipantCanceledWhenConcurrentInsertCollidesRetriesConditionalCancel() {
        // given
        LocalDateTime canceledAt = LocalDateTime.of(2026, 10, 20, 19, 50, 0);
        given(reminderRepository.cancelScheduledByParticipantId(411L, fixedNow)).willReturn(0).willReturn(1);
        given(
            reminderRepository
                .insertIgnoreReminder(
                    7L,
                    55L,
                    411L,
                    canceledAt,
                    canceledAt,
                    3,
                    "evt-cancel-1",
                    ReminderStatus.CANCELED.name(),
                    fixedNow
                )
        ).willReturn(0);

        // when
        examReminderService.cancelByParticipantCanceled("evt-cancel-1", 7L, 55L, 411L, 3, canceledAt);

        // then
        verify(reminderRepository, times(2)).cancelScheduledByParticipantId(411L, fixedNow);
    }

    @Test
    @DisplayName("7.1 목록 조회 후 ExamUpdated로 fireAt이 미래로 연기되어 markAsSentIfScheduled가 0건을 반환하면 알림을 발송하지 않는다")
    void dispatchSingleReminderWhenPostponedAfterFetchDoesNotCreateNotification() {
        // given
        NotificationReminder reminder
            = NotificationReminder
                .createScheduled(7L, 55L, 411L, LocalDateTime.of(2026, 10, 20, 20, 3, 0), 3, "evt-reg-1");
        ReflectionTestUtils.setField(reminder, "id", 10L);

        given(reminderRepository.markAsSentIfScheduled(eq(10L), eq(fixedNow))).willReturn(0);

        // when
        boolean dispatched = examReminderService.dispatchSingleReminder(reminder);

        // then
        assertThat(dispatched).isFalse();
        verify(notificationRepository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publishEvent(any());
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

    @Test
    @DisplayName("MemberWithdrawn 수신 시 notification_withdrawn_members에 탈퇴 기록을 남기고 해당 회원의 실제 참가 예약을 삭제한다")
    void cleanupByMemberWithdrawnInsertsWithdrawnRecordAndDeletesParticipantReminders() {
        // given
        LocalDateTime withdrawnAt = LocalDateTime.of(2026, 10, 21, 10, 0, 0);

        // when
        examReminderService.cleanupByMemberWithdrawn("evt-withdraw-1", 7L, 12, withdrawnAt);

        // then
        verify(withdrawnMemberRepository)
            .insertIgnoreWithdrawnMember(eq(7L), eq("evt-withdraw-1"), eq(12), eq(withdrawnAt), eq(fixedNow));
        verify(reminderRepository).deleteParticipantRemindersByMemberId(7L);
    }

    @Test
    @DisplayName("이미 탈퇴 기록이 존재하는 회원의 지연 ExamParticipantRegistered 이벤트는 예약을 생성하지 않는다")
    void registerReminderWhenMemberAlreadyWithdrawnSkipsInsert() {
        // given
        LocalDateTime startsAt = LocalDateTime.of(2026, 10, 20, 21, 0, 0);
        NotificationWithdrawnMember withdrawnMember
            = NotificationWithdrawnMember.recordWithdrawal(7L, "evt-withdraw-1", 12, startsAt, fixedNow);
        given(withdrawnMemberRepository.findByMemberIdForShare(7L)).willReturn(Optional.of(withdrawnMember));

        // when
        examReminderService.registerReminder("evt-late-reg", 7L, 55L, 411L, 1, startsAt);

        // then
        verify(reminderRepository, never())
            .insertIgnoreReminder(any(), any(), any(), any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    @DisplayName("다수 수신자 중 탈퇴 기록이 있는 회원 ID 집합을 단건 IN 공유 잠금(FOR SHARE) 쿼리로 일괄 조회한다")
    void findWithdrawnMemberIdsQueriesInBatchWithShareLock() {
        // given
        java.util.List<Long> memberIds = java.util.List.of(7L, 8L, 9L);
        given(withdrawnMemberRepository.findMemberIdsByMemberIdInForShare(memberIds))
            .willReturn(java.util.List.of(7L, 9L));

        // when
        java.util.Set<Long> withdrawnMemberIds = examReminderService.findWithdrawnMemberIds(memberIds);

        // then
        assertThat(withdrawnMemberIds).containsExactlyInAnyOrder(7L, 9L);
        verify(withdrawnMemberRepository).findMemberIdsByMemberIdInForShare(memberIds);
    }
}
