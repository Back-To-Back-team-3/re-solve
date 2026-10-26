package com.backtoback.study.replica.domain;

import java.time.LocalDateTime;

import com.backtoback.common.jpa.entity.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원 AC 복제본(ERD 4.5.9 member_ac_replicas). {@code SubmissionJudged}(PRACTICE) 이벤트로 유지하며,
 * 반영 조건(들어온 {@code judgeAttempt}가 저장된 값보다 큰 경우만 반영)은 이벤트 소비 기능 Issue에서 구현한다.
 */
@Getter
@Entity
@Table(
    name = "member_ac_replicas",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_member_ac_replicas_submission",
            columnNames = "submission_id"
        )
    },
    indexes = {
        @Index(
            name = "idx_member_ac_replicas_member_problem_accepted",
            columnList = "member_id, problem_id, is_accepted, submitted_at"
        )
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberAcReplica extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
        name = "submission_id",
        nullable = false
    )
    private Long submissionId;

    @Column(
        name = "member_id",
        nullable = false
    )
    private Long memberId;

    @Column(
        name = "problem_id",
        nullable = false
    )
    private Long problemId;

    @Column(
        name = "submitted_at",
        nullable = false
    )
    private LocalDateTime submittedAt;

    @Column(
        name = "judge_attempt",
        nullable = false
    )
    private int judgeAttempt;

    @Enumerated(EnumType.STRING)
    @Column(
        nullable = false,
        length = 30
    )
    private JudgeVerdict verdict;

    @Column(
        name = "is_accepted",
        nullable = false
    )
    private boolean accepted;

    @Column(
        name = "synced_at",
        nullable = false
    )
    private LocalDateTime syncedAt;

    private MemberAcReplica(
        Long submissionId,
        Long memberId,
        Long problemId,
        LocalDateTime submittedAt,
        int judgeAttempt,
        JudgeVerdict verdict,
        LocalDateTime syncedAt
    ) {
        this.submissionId = submissionId;
        this.memberId = memberId;
        this.problemId = problemId;
        this.submittedAt = submittedAt;
        this.judgeAttempt = judgeAttempt;
        this.verdict = verdict;
        this.accepted = verdict == JudgeVerdict.AC;
        this.syncedAt = syncedAt;
    }

    /**
     * 제출 1건의 채점 결과 복제본을 만든다. 같은 제출의 지연·중복 반영 비교는 이벤트 소비 기능에서 처리한다(ERD 4.5.9).
     */
    public static MemberAcReplica sync(
        Long submissionId,
        Long memberId,
        Long problemId,
        LocalDateTime submittedAt,
        int judgeAttempt,
        JudgeVerdict verdict,
        LocalDateTime syncedAt
    ) {
        return new MemberAcReplica(submissionId, memberId, problemId, submittedAt, judgeAttempt, verdict, syncedAt);
    }
}
