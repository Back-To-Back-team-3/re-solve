package com.backtoback.contest.exam.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.backtoback.common.jpa.entity.BaseTimeEntity;

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
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 시험 참가와 최초 입장 상태를 보관한다 (ERD 4.4.3). */
@Getter
@Entity
@Table(name = "exam_participants")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExamParticipant extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(
        fetch = FetchType.LAZY,
        optional = false
    )
    @JoinColumn(
        name = "exam_id",
        nullable = false
    )
    private Exam exam;
    @Column(nullable = false)
    private Long memberId;
    @Enumerated(EnumType.STRING)
    @Column(
        nullable = false,
        length = 30
    )
    private ExamParticipantStatus status;
    @Column(nullable = false)
    private long participantRevision;
    @Column(nullable = false)
    private LocalDateTime registeredAt;
    private LocalDateTime enteredAt;
    @Column(nullable = false)
    private LocalDateTime personalEndsAt;
    private LocalDateTime finishedAt;
    @Column(
        nullable = false,
        precision = 6,
        scale = 2
    )
    private BigDecimal totalScore;
    private LocalDateTime lastValidSubmittedAt;
    private LocalDateTime autoSubmittedAt;
    @Column(nullable = false)
    private boolean isRanked;
    private Integer finalRank;
    @Version
    @Column(nullable = false)
    private long version;

    private ExamParticipant(Exam exam, long memberId, LocalDateTime now) {
        this.exam = exam;
        this.memberId = memberId;
        this.status = ExamParticipantStatus.REGISTERED;
        this.participantRevision = 1;
        this.registeredAt = now;
        this.personalEndsAt = exam.getEndsAt();
        this.totalScore = BigDecimal.ZERO;
    }

    public static ExamParticipant register(Exam exam, long memberId, LocalDateTime now) {
        return new ExamParticipant(exam, memberId, now);
    }

    /** 최초 입장에만 이력 버전을 증가시킨다 (API 명세서 4.2.2). */
    public void enter(LocalDateTime now) {
        if (status != ExamParticipantStatus.REGISTERED) {
            throw new IllegalStateException("Participant is not registered");
        }
        enteredAt = now;
        status = ExamParticipantStatus.STARTED;
        participantRevision = Math.incrementExact(participantRevision);
    }

    /** 취소 후 재등록은 새 행으로 생성한다 (ERD 4.4.3). */
    public void cancel() {
        if (status != ExamParticipantStatus.REGISTERED) {
            throw new IllegalStateException("Participant is not registered");
        }
        status = ExamParticipantStatus.CANCELED;
        participantRevision = Math.incrementExact(participantRevision);
    }
}
