package com.backtoback.judge.judging.domain;

import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.util.Assert;

import com.backtoback.common.jpa.entity.BaseTimeEntity;
import com.backtoback.judge.submission.domain.Submission;

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
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 제출의 특정 채점 회차를 처리하는 작업을 저장한다. */
@Entity
@Table(name = "judge_jobs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JudgeJob extends BaseTimeEntity {
    /** 제출 대표 상태와 구분되는 작업 상태다. */
    public enum Status {
        PENDING, RUNNING, RETRY_WAITING, SUCCEEDED, FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(
        fetch = FetchType.LAZY,
        optional = false
    )
    @JoinColumn(
        name = "submission_id",
        nullable = false,
        updatable = false
    )
    private Submission submission;
    @Column(
        nullable = false,
        updatable = false
    )
    private int judgeAttempt;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(
        nullable = false,
        length = 30
    )
    private Status status;
    @Column(nullable = false)
    private int tryCount;
    @Column(length = 64)
    private String leaseToken;
    @Column(columnDefinition = "DATETIME(6)")
    private LocalDateTime leaseUntil;
    @Column(
        nullable = false,
        columnDefinition = "DATETIME(6)"
    )
    private LocalDateTime nextRunAt;
    @Column(
        nullable = false,
        columnDefinition = "DATETIME(6)",
        updatable = false
    )
    private LocalDateTime deadlineAt;
    @Column(columnDefinition = "DATETIME(6)")
    private LocalDateTime startedAt;
    @Column(columnDefinition = "DATETIME(6)")
    private LocalDateTime finishedAt;
    @Column(length = 500)
    private String lastError;

    /**
     * 제출의 현재 회차를 최초 실행 대기로 만든다.
     *
     * @param submission 저장한 제출
     * @param nextRunAt 선점 가능 시각
     * @param deadlineAt 호출자가 확정한 처리 기한
     */
    private JudgeJob(Submission submission, LocalDateTime nextRunAt, LocalDateTime deadlineAt) {
        Assert.notNull(submission, "작업의 제출은 필수입니다.");
        Assert.notNull(submission.getId(), "저장한 제출만 작업을 만들 수 있습니다.");
        Assert.notNull(nextRunAt, "선점 가능 시각은 필수입니다.");
        Assert.isTrue(deadlineAt != null && deadlineAt.isAfter(nextRunAt), "처리 기한은 선점 가능 시각 이후여야 합니다.");
        this.submission = submission;
        judgeAttempt = submission.getJudgeAttempt();
        status = Status.PENDING;
        tryCount = 1;
        this.nextRunAt = nextRunAt;
        this.deadlineAt = deadlineAt;
    }

    /**
     * 제출과 같은 트랜잭션에서 첫 채점 작업을 생성한다.
     *
     * @param submission 영속 제출
     * @param nextRunAt 최초 선점 가능 시각
     * @param deadlineAt 전체 처리 기한
     * @return 첫 실행 시도의 대기 작업
     * @throws IllegalArgumentException 제출이나 처리 시각이 올바르지 않은 경우
     */
    public static JudgeJob pending(Submission submission, LocalDateTime nextRunAt, LocalDateTime deadlineAt) {
        return new JudgeJob(submission, nextRunAt, deadlineAt);
    }
}
