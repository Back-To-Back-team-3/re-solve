package com.backtoback.contest.exam.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 참가 상태의 불변 전이 이력이다 (ERD 4.4.4). */
@Entity
@Table(name = "exam_participant_histories")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExamParticipantHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(
        fetch = FetchType.LAZY,
        optional = false
    )
    @JoinColumn(
        name = "exam_participant_id",
        nullable = false
    )
    private ExamParticipant participant;
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ExamParticipantStatus fromStatus;
    @Enumerated(EnumType.STRING)
    @Column(
        nullable = false,
        length = 30
    )
    private ExamParticipantStatus toStatus;
    private Long actorMemberId;
    @Column(length = 500)
    private String reason;
    @Column(
        nullable = false,
        updatable = false
    )
    private LocalDateTime createdAt;

    private ExamParticipantHistory(
        ExamParticipant participant,
        ExamParticipantStatus fromStatus,
        ExamParticipantStatus toStatus,
        long actorMemberId,
        LocalDateTime now
    ) {
        this.participant = participant;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actorMemberId = actorMemberId;
        this.createdAt = now;
    }

    public static ExamParticipantHistory transition(
        ExamParticipant participant,
        ExamParticipantStatus fromStatus,
        ExamParticipantStatus toStatus,
        long actorMemberId,
        LocalDateTime now
    ) {
        return new ExamParticipantHistory(participant, fromStatus, toStatus, actorMemberId, now);
    }
}
