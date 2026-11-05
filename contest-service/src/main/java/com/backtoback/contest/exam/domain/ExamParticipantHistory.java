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

/**
 * 참가의 이전·이후 상태와 처리자, 전이 시각을 불변 이력으로 보관한다.
 * <p>기준 문서</p>
 * <ul><li>도메인 및 데이터베이스 v1.0 / §4.4.4 참가자 상태 이력: 최초 상태와 전이 기록</li></ul>
 */
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

    /**
     * 불변 이력에 전이 당시 상태와 처리자를 고정한다.
     *
     * @param participant 전이한 참가
     * @param fromStatus 이전 상태, 최초 등록이면 null
     * @param toStatus 전이 후 상태
     * @param actorMemberId 전이 요청 회원
     * @param now 서버 전이 시각
     */
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

    /**
     * 이력 객체를 생성하며 저장은 호출자의 트랜잭션에서 수행한다.
     *
     * @param participant 전이한 참가
     * @param fromStatus 이전 상태, 최초 등록이면 null
     * @param toStatus 전이 후 상태
     * @param actorMemberId 전이 요청 회원
     * @param now 서버 전이 시각
     * @return 아직 저장하지 않은 전이 이력
     */
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
