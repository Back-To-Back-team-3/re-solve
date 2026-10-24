package com.backtoback.notification.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.notification.delivery.service.NotificationDeliveryService;
import com.backtoback.notification.event.domain.ProcessedEvent;
import com.backtoback.notification.event.dto.NotificationCreatedEvent;
import com.backtoback.notification.event.repository.ProcessedEventRepository;
import com.backtoback.notification.notification.domain.Notification;
import com.backtoback.notification.notification.domain.NotificationType;
import com.backtoback.notification.notification.repository.NotificationRepository;
import com.backtoback.notification.preference.domain.NotificationPreference;
import com.backtoback.notification.preference.repository.NotificationPreferenceRepository;
import com.backtoback.notification.reminder.service.ExamReminderService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class NotificationEventProcessorTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ClockConfiguration.SERVICE_ZONE);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private NotificationPreferenceRepository preferenceRepository;

    @Mock
    private NotificationDeliveryService deliveryService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private ExamReminderService examReminderService;

    private NotificationEventProcessor eventProcessor;

    @BeforeEach
    void setUp() {
        eventProcessor
            = new NotificationEventProcessor(
                processedEventRepository,
                notificationRepository,
                preferenceRepository,
                deliveryService,
                eventPublisher,
                examReminderService,
                clock
            );
    }

    @Test
    @DisplayName("SubmissionJudged 이벤트 수신 시 알림 엔티티를 저장하고 커밋 후 발송 이벤트를 발행한다")
    void processSubmissionJudgedCreatesNotification() {
        // given
        String eventId = "evt-judged-1";
        JsonNode payload = objectMapper.readTree("""
            {
              "eventId": "evt-judged-1",
              "eventType": "SubmissionJudged",
              "schemaVersion": "v1",
              "occurredAt": "2026-10-20T21:00:00.000000+09:00",
              "aggregateId": "91023",
              "correlationId": "corr-1",
              "submissionId": "91023",
              "userId": "7",
              "problemId": "1001",
              "problemRevisionId": "3",
              "verdict": "AC",
              "unknownFutureField": "ignored"
            }
            """);

        given(processedEventRepository.existsByEventId(eventId)).willReturn(false);
        given(
            notificationRepository
                .existsBySourceEventIdAndMemberIdAndType(eventId, 7L, NotificationType.SUBMISSION_JUDGED)
        ).willReturn(false);
        given(notificationRepository.saveAndFlush(any(Notification.class))).willAnswer(invocation -> {
            Notification saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 500L);
            return saved;
        });

        // when
        eventProcessor.process(eventId, "SubmissionJudged", payload);

        // then
        verify(processedEventRepository).saveAndFlush(any(ProcessedEvent.class));

        ArgumentCaptor<Notification> notificationCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).saveAndFlush(notificationCaptor.capture());

        Notification captured = notificationCaptor.getValue();
        assertThat(captured.getMemberId()).isEqualTo(7L);
        assertThat(captured.getSourceEventId()).isEqualTo(eventId);
        assertThat(captured.getType()).isEqualTo(NotificationType.SUBMISSION_JUDGED);
        assertThat(captured.getTitle()).isEqualTo("채점 완료");
        assertThat(captured.getContent()).isEqualTo("1001번 문제 채점이 완료되었습니다. (결과: AC)");
        assertThat(captured.getLinkUrl()).isEqualTo("/submissions/91023");

        ArgumentCaptor<NotificationCreatedEvent> createdEventCaptor
            = ArgumentCaptor.forClass(NotificationCreatedEvent.class);
        verify(eventPublisher).publishEvent(createdEventCaptor.capture());
        assertThat(createdEventCaptor.getValue().memberId()).isEqualTo(7L);
        assertThat(createdEventCaptor.getValue().notification().getId()).isEqualTo(500L);
    }

    @Test
    @DisplayName("SubmissionFailed 이벤트 수신 시 문맥에 맞는 이동 경로로 실패 알림을 저장한다")
    void processSubmissionFailedCreatesNotification() {
        // given
        String eventId = "evt-failed-1";
        JsonNode payload = objectMapper.readTree("""
            {
              "eventId": "evt-failed-1",
              "eventType": "SubmissionFailed",
              "context": "EXAM",
              "contextId": "55",
              "submissionId": "91024",
              "userId": "7",
              "problemId": "1002",
              "errorCode": "JUDGE0_TIMEOUT"
            }
            """);

        given(processedEventRepository.existsByEventId(eventId)).willReturn(false);
        given(
            notificationRepository
                .existsBySourceEventIdAndMemberIdAndType(eventId, 7L, NotificationType.SUBMISSION_FAILED)
        ).willReturn(false);
        given(notificationRepository.saveAndFlush(any(Notification.class)))
            .willAnswer(invocation -> invocation.getArgument(0));

        // when
        eventProcessor.process(eventId, "SubmissionFailed", payload);

        // then
        ArgumentCaptor<Notification> notificationCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).saveAndFlush(notificationCaptor.capture());

        Notification captured = notificationCaptor.getValue();
        assertThat(captured.getType()).isEqualTo(NotificationType.SUBMISSION_FAILED);
        assertThat(captured.getTitle()).isEqualTo("채점 실패");
        assertThat(captured.getContent()).isEqualTo("1002번 문제 채점 실패, 재처리 예정입니다.");
        assertThat(captured.getLinkUrl()).isEqualTo("/exams/55");
    }

    @Test
    @DisplayName("HintReady 이벤트 수신 시 수신 설정이 켜져 있으면 힌트 준비 알림을 저장한다")
    void processHintReadyCreatesNotificationWhenEnabled() {
        // given
        String eventId = "evt-hint-1";
        JsonNode payload = objectMapper.readTree("""
            {
              "eventId": "evt-hint-1",
              "eventType": "HintReady",
              "hintRequestId": "301",
              "memberId": "7",
              "problemId": "1001",
              "level": 2,
              "result": "READY",
              "isCached": false
            }
            """);

        given(processedEventRepository.existsByEventId(eventId)).willReturn(false);
        given(preferenceRepository.findByMemberId(7L)).willReturn(Optional.empty());
        given(notificationRepository.existsBySourceEventIdAndMemberIdAndType(eventId, 7L, NotificationType.HINT_READY))
            .willReturn(false);
        given(notificationRepository.saveAndFlush(any(Notification.class)))
            .willAnswer(invocation -> invocation.getArgument(0));

        // when
        eventProcessor.process(eventId, "HintReady", payload);

        // then
        ArgumentCaptor<Notification> notificationCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).saveAndFlush(notificationCaptor.capture());

        Notification captured = notificationCaptor.getValue();
        assertThat(captured.getType()).isEqualTo(NotificationType.HINT_READY);
        assertThat(captured.getTitle()).isEqualTo("힌트 준비 완료");
        assertThat(captured.getContent()).isEqualTo("1001번 문제의 2단계 힌트가 준비되었습니다.");
        assertThat(captured.getLinkUrl()).isEqualTo("/problems/1001?hint=301");
    }

    @Test
    @DisplayName("ExamParticipantRegistered 이벤트 수신 시 시험 시작 임박 예약 등록을 위임하고 즉시 알림은 생성하지 않는다")
    void processExamParticipantRegisteredRegistersReminderOnly() {
        // given
        String eventId = "evt-exam-reg-1";
        JsonNode payload = objectMapper.readTree("""
            {
              "eventId": "evt-exam-reg-1",
              "eventType": "ExamParticipantRegistered",
              "context": "EXAM",
              "contextId": "55",
              "examId": "55",
              "participantId": "411",
              "memberId": "7",
              "participantRevision": "1",
              "contextRevision": "3",
              "problemIds": ["1001"],
              "startsAt": "2026-10-20T22:00:00.000000+09:00",
              "endsAt": "2026-10-20T23:30:00.000000+09:00"
            }
            """);

        given(processedEventRepository.existsByEventId(eventId)).willReturn(false);

        // when
        eventProcessor.process(eventId, "ExamParticipantRegistered", payload);

        // then
        verify(processedEventRepository).saveAndFlush(any(ProcessedEvent.class));
        verify(
            examReminderService
        ).registerReminder(eq(eventId), eq(7L), eq(55L), eq(411L), eq(3), eq(LocalDateTime.of(2026, 10, 20, 22, 0, 0)));
        verify(notificationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("ExamUpdated 이벤트 수신 시 예약 재설정과 EXAM_UPDATED 알림 생성을 함께 수행한다")
    void processExamUpdatedReschedulesReminderAndCreatesNotification() {
        // given
        String eventId = "evt-exam-upd-1";
        JsonNode payload = objectMapper.readTree("""
            {
              "eventId": "evt-exam-upd-1",
              "eventType": "ExamUpdated",
              "examId": "55",
              "studyId": "12",
              "examRevision": "4",
              "startsAt": "2026-10-20T22:30:00.000000+09:00",
              "endsAt": "2026-10-21T00:00:00.000000+09:00",
              "targetMemberIds": ["7"]
            }
            """);

        given(processedEventRepository.existsByEventId(eventId)).willReturn(false);
        given(preferenceRepository.findByMemberId(7L)).willReturn(Optional.empty());
        given(
            notificationRepository.existsBySourceEventIdAndMemberIdAndType(eventId, 7L, NotificationType.EXAM_UPDATED)
        ).willReturn(false);
        given(notificationRepository.saveAndFlush(any(Notification.class)))
            .willAnswer(invocation -> invocation.getArgument(0));

        // when
        eventProcessor.process(eventId, "ExamUpdated", payload);

        // then
        verify(examReminderService)
            .rescheduleByExamUpdated(eq(eventId), eq(55L), eq(4), eq(LocalDateTime.of(2026, 10, 20, 22, 30, 0)));
        verify(notificationRepository).saveAndFlush(any(Notification.class));
    }

    @Test
    @DisplayName("ExamParticipantCanceled 이벤트 수신 시 참가자의 시작 임박 예약 무효화를 위임한다")
    void processExamParticipantCanceledCancelsReminder() {
        // given
        String eventId = "evt-exam-part-cancel-1";
        JsonNode payload = objectMapper.readTree("""
            {
              "eventId": "evt-exam-part-cancel-1",
              "eventType": "ExamParticipantCanceled",
              "context": "EXAM",
              "contextId": "55",
              "examId": "55",
              "participantId": "411",
              "memberId": "7",
              "participantRevision": "2",
              "contextRevision": "3",
              "canceledAt": "2026-10-20T20:10:00.000000+09:00"
            }
            """);

        given(processedEventRepository.existsByEventId(eventId)).willReturn(false);

        // when
        eventProcessor.process(eventId, "ExamParticipantCanceled", payload);

        // then
        verify(examReminderService)
            .cancelByParticipantCanceled(
                eq(eventId),
                eq(7L),
                eq(55L),
                eq(411L),
                eq(3),
                eq(LocalDateTime.of(2026, 10, 20, 20, 10, 0))
            );
        verify(notificationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("ExamCanceled 이벤트 수신 시 시험 예약 일괄 취소와 EXAM_CANCELED 알림 생성을 함께 수행한다")
    void processExamCanceledCancelsRemindersAndCreatesNotification() {
        // given
        String eventId = "evt-exam-cancel-1";
        JsonNode payload = objectMapper.readTree("""
            {
              "eventId": "evt-exam-cancel-1",
              "eventType": "ExamCanceled",
              "examId": "55",
              "studyId": "12",
              "examRevision": "4",
              "canceledAt": "2026-10-20T20:15:00.000000+09:00",
              "reason": "LEADER_CANCELED",
              "targetMemberIds": ["7"]
            }
            """);

        given(processedEventRepository.existsByEventId(eventId)).willReturn(false);
        given(preferenceRepository.findByMemberId(7L)).willReturn(Optional.empty());
        given(
            notificationRepository.existsBySourceEventIdAndMemberIdAndType(eventId, 7L, NotificationType.EXAM_CANCELED)
        ).willReturn(false);
        given(notificationRepository.saveAndFlush(any(Notification.class)))
            .willAnswer(invocation -> invocation.getArgument(0));

        // when
        eventProcessor.process(eventId, "ExamCanceled", payload);

        // then
        verify(examReminderService).cancelByExamCanceled(55L);
        verify(notificationRepository).saveAndFlush(any(Notification.class));
    }

    @Test
    @DisplayName("수신 설정이 비활성화된 카테고리의 이벤트는 processed_events만 기록하고 알림은 생성하지 않는다")
    void processSkipsNotificationWhenPreferenceDisabled() {
        // given
        String eventId = "evt-hint-disabled";
        JsonNode payload = objectMapper.readTree("""
            {
              "eventId": "evt-hint-disabled",
              "eventType": "HintReady",
              "hintRequestId": "302",
              "memberId": "7",
              "problemId": "1001",
              "level": 1,
              "result": "READY"
            }
            """);

        NotificationPreference preference = NotificationPreference.createDefault(7L);
        preference.update(null, null, null, null, false, null);

        given(processedEventRepository.existsByEventId(eventId)).willReturn(false);
        given(preferenceRepository.findByMemberId(7L)).willReturn(Optional.of(preference));

        // when
        eventProcessor.process(eventId, "HintReady", payload);

        // then
        verify(processedEventRepository).saveAndFlush(any(ProcessedEvent.class));
        verify(notificationRepository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("이미 처리된 중복 eventId 수신 시 알림을 중복 저장하지 않는다")
    void processDuplicateEventIdSkipsProcessing() {
        // given
        String eventId = "evt-dup-1";
        JsonNode payload = objectMapper.readTree("""
            {
              "eventId": "evt-dup-1",
              "eventType": "SubmissionJudged",
              "submissionId": "91023",
              "userId": "7",
              "problemId": "1001",
              "verdict": "AC"
            }
            """);

        given(processedEventRepository.existsByEventId(eventId)).willReturn(true);

        // when
        eventProcessor.process(eventId, "SubmissionJudged", payload);

        // then
        verify(processedEventRepository, never()).saveAndFlush(any());
        verify(notificationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("알림 생성 대상이 아닌 미지원 이벤트 타입은 processed_events만 기록하고 정상 종료한다")
    void processUnknownEventTypeOnlyRecordsProcessedEvent() {
        // given
        String eventId = "evt-unknown-1";
        JsonNode payload = objectMapper.readTree("""
            {
              "eventId": "evt-unknown-1",
              "eventType": "UnknownDomainEvent"
            }
            """);

        given(processedEventRepository.existsByEventId(eventId)).willReturn(false);

        // when
        eventProcessor.process(eventId, "UnknownDomainEvent", payload);

        // then
        verify(processedEventRepository).saveAndFlush(any(ProcessedEvent.class));
        verify(notificationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("필수 필드가 누락된 이벤트 페이로드는 IllegalArgumentException을 던진다")
    void processMissingRequiredFieldThrowsException() {
        // given
        String eventId = "evt-invalid-1";
        JsonNode payload = objectMapper.readTree("""
            {
              "eventId": "evt-invalid-1",
              "eventType": "SubmissionJudged",
              "userId": "7"
            }
            """);

        given(processedEventRepository.existsByEventId(eventId)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> eventProcessor.process(eventId, "SubmissionJudged", payload))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("트랜잭션 커밋 후 이벤트 리스너가 호출되면 NotificationDeliveryService로 실시간 전송을 위임한다")
    void onNotificationCreatedDelegatesToDeliveryService() {
        // given
        Notification notification
            = Notification
                .builder()
                .memberId(7L)
                .sourceEventId("evt-1")
                .type(NotificationType.SUBMISSION_JUDGED)
                .title("채점 완료")
                .content("내용")
                .build();
        NotificationCreatedEvent createdEvent = new NotificationCreatedEvent(7L, notification);

        // when
        eventProcessor.onNotificationCreated(createdEvent);

        // then
        verify(deliveryService).sendToMember(eq(7L), eq(notification));
    }
}
