package com.backtoback.notification.reminder.domain;

import java.time.LocalDateTime;

import com.backtoback.common.jpa.entity.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 시험 시작 5분 전 임박 알림 발화를 위한 예약 엔티티 (스케줄러 명세서 7.1).
 */
@Entity
@Table(
    name = "notification_reminders",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_notification_reminders_participant_id",
            columnNames = {
                "participant_id"
            }
        )
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationReminder extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
        name = "member_id",
        nullable = false
    )
    private Long memberId;

    @Column(
        name = "exam_id",
        nullable = false
    )
    private Long examId;

    @Column(
        name = "participant_id",
        nullable = false
    )
    private Long participantId;

    @Column(
        name = "fire_at",
        nullable = false
    )
    private LocalDateTime fireAt;

    @Column(
        name = "starts_at",
        nullable = false
    )
    private LocalDateTime startsAt;

    @Column(
        name = "context_revision",
        nullable = false
    )
    private int contextRevision;

    @Column(
        name = "source_event_id",
        nullable = false,
        length = 64
    )
    private String sourceEventId;

    @Enumerated(EnumType.STRING)
    @Column(
        name = "status",
        nullable = false,
        length = 30
    )
    private ReminderStatus status;

    @Builder
    public NotificationReminder(
        Long memberId,
        Long examId,
        Long participantId,
        LocalDateTime fireAt,
        LocalDateTime startsAt,
        int contextRevision,
        String sourceEventId,
        ReminderStatus status
    ) {
        this.memberId = memberId;
        this.examId = examId;
        this.participantId = participantId;
        this.fireAt = fireAt;
        this.startsAt = startsAt;
        this.contextRevision = contextRevision;
        this.sourceEventId = sourceEventId;
        this.status = status;
    }

    /**
     * 시험 참가 등록 이벤트로부터 발화 대기 상태({@link ReminderStatus#SCHEDULED})의 예약을 생성한다.
     */
    public static NotificationReminder createScheduled(
        Long memberId,
        Long examId,
        Long participantId,
        LocalDateTime startsAt,
        int contextRevision,
        String sourceEventId
    ) {
        return NotificationReminder
            .builder()
            .memberId(memberId)
            .examId(examId)
            .participantId(participantId)
            .fireAt(startsAt.minusMinutes(5))
            .startsAt(startsAt)
            .contextRevision(contextRevision)
            .sourceEventId(sourceEventId)
            .status(ReminderStatus.SCHEDULED)
            .build();
    }

    /**
     * 참가 취소 이벤트가 등록 이벤트보다 먼저 도착한 경우 역전 방지를 위한 취소 표식({@link ReminderStatus#CANCELED}) 행을 생성한다.
     */
    public static NotificationReminder createCanceledTombstone(
        Long memberId,
        Long examId,
        Long participantId,
        LocalDateTime canceledAt,
        int contextRevision,
        String sourceEventId
    ) {
        return NotificationReminder
            .builder()
            .memberId(memberId)
            .examId(examId)
            .participantId(participantId)
            .fireAt(canceledAt)
            .startsAt(canceledAt)
            .contextRevision(contextRevision)
            .sourceEventId(sourceEventId)
            .status(ReminderStatus.CANCELED)
            .build();
    }

    /**
     * {@code ExamUpdated} 수신 시 예약이 아직 {@link ReminderStatus#SCHEDULED}이고
     * 수신한 {@code incomingRevision}이 저장된 버전보다 큰 경우에만 발화 시각과 원천 이벤트 ID를 갱신한다.
     *
     * @param newStartsAt 변경된 시험 시작 시각
     * @param incomingRevision 변경된 시험 설정 버전
     * @param newSourceEventId ExamUpdated 이벤트 ID
     * @return 갱신 여부
     */
    public boolean rescheduleIfNewer(LocalDateTime newStartsAt, int incomingRevision, String newSourceEventId) {
        if (this.status != ReminderStatus.SCHEDULED || incomingRevision <= this.contextRevision) {
            return false;
        }
        this.startsAt = newStartsAt;
        this.fireAt = newStartsAt.minusMinutes(5);
        this.contextRevision = incomingRevision;
        this.sourceEventId = newSourceEventId;
        return true;
    }

    /**
     * 예약을 취소 상태({@link ReminderStatus#CANCELED})로 전이한다.
     */
    public void cancel() {
        if (this.status == ReminderStatus.SCHEDULED) {
            this.status = ReminderStatus.CANCELED;
        }
    }
}
