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

/**
 * 시험 참가 상태와 최초 입장 시각을 보관하고 상태 전이의 버전을 증가시킨다.
 * <p>기준 문서</p>
 * <ul><li>도메인 및 데이터베이스 v1.0 / §4.4.3 시험 참가자: 참가 상태와 개인 마감</li></ul>
 */
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

    /**
     * 생성 시 개인 마감을 시험 전체 마감으로 고정한다 (정책 및 상태 5.1).
     *
     * @param exam 참가할 시험
     * @param memberId 참가 회원 식별자
     * @param now 서버 등록 시각
     */
    private ExamParticipant(Exam exam, long memberId, LocalDateTime now) {
        this.exam = exam;
        this.memberId = memberId;
        this.status = ExamParticipantStatus.REGISTERED;
        this.participantRevision = 1;
        this.registeredAt = now;
        this.personalEndsAt = exam.getEndsAt();
        this.totalScore = BigDecimal.ZERO;
    }

    /**
     * 새 참가 행을 생성하며 DB 저장은 호출자가 수행한다.
     *
     * @param exam 참가할 시험
     * @param memberId 참가 회원 식별자
     * @param now 서버 등록 시각
     * @return 등록 상태와 초기 버전을 가진 참가
     */
    public static ExamParticipant register(Exam exam, long memberId, LocalDateTime now) {
        return new ExamParticipant(exam, memberId, now);
    }

    /**
     * 최초 입장에만 이력 버전을 증가시킨다 (API 명세서 4.2.2).
     *
     * @param now 최초 입장 서버 시각
     * @throws IllegalStateException 등록 상태가 아닐 때
     */
    public void enter(LocalDateTime now) {
        if (status != ExamParticipantStatus.REGISTERED) {
            throw new IllegalStateException("Participant is not registered");
        }
        enteredAt = now;
        status = ExamParticipantStatus.STARTED;
        participantRevision = Math.incrementExact(participantRevision);
    }

    /**
     * 취소 후 재등록은 새 행으로 생성한다 (ERD 4.4.3).
     *
     * @throws IllegalStateException 등록 상태가 아닐 때
     */
    public void cancel() {
        if (status != ExamParticipantStatus.REGISTERED) {
            throw new IllegalStateException("Participant is not registered");
        }
        status = ExamParticipantStatus.CANCELED;
        participantRevision = Math.incrementExact(participantRevision);
    }
}
