package com.backtoback.contest.exam.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import com.backtoback.contest.global.entity.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원의 시험 참가와 개인 마감·결과 정보를 저장한다. 상태 전이와 이력 기록은 서비스에서 처리한다.
 */
@Getter
@Entity
@Table(
    name = "exam_participants",
    indexes = @Index(
        name = "idx_exam_participants_member_status",
        columnList = "member_id, status"
    ),
    uniqueConstraints = @UniqueConstraint(
        name = "uk_exam_participants_exam_active_member",
        columnNames = {
            "exam_id",
            "active_member_id"
        }
    )
)
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
        nullable = false,
        foreignKey = @ForeignKey(name = "fk_exam_participants_exam")
    )
    private Exam exam;

    @Column(nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(
        nullable = false,
        length = 30,
        columnDefinition = "VARCHAR(30)"
    )
    private ExamParticipantStatus status;

    // 취소된 참가는 NULL로 계산해 같은 회원의 새 참가 행을 허용한다.
    @Generated(
        event = {
            EventType.INSERT,
            EventType.UPDATE
        }
    )
    @Column(
        insertable = false,
        updatable = false
    )
    private Long activeMemberId;

    @Column(nullable = false)
    private Long participantRevision;

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

    @Column(
        name = "is_ranked",
        nullable = false
    )
    private boolean ranked;

    private Integer finalRank;

    @Version
    @Column(nullable = false)
    private Long version;

    private ExamParticipant(Exam exam, Long memberId, LocalDateTime registeredAt, LocalDateTime personalEndsAt) {
        this.exam = exam;
        this.memberId = memberId;
        this.status = ExamParticipantStatus.REGISTERED;
        this.participantRevision = 1L;
        this.registeredAt = registeredAt;
        this.personalEndsAt = personalEndsAt;
        this.totalScore = BigDecimal.ZERO;
    }

    /**
     * 저장할 참가 모델을 만든다. 참가 자격과 개인 마감 시간은 서비스에서 결정한다.
     */
    public static ExamParticipant create(
        Exam exam,
        Long memberId,
        LocalDateTime registeredAt,
        LocalDateTime personalEndsAt
    ) {
        return new ExamParticipant(exam, memberId, registeredAt, personalEndsAt);
    }
}
