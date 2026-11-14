package com.backtoback.judge.judging.domain;

import java.time.LocalDateTime;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.util.Assert;

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

/** 종결한 실행 시도의 결과를 수정 없이 보존한다. */
@Entity
@Table(name = "judge_job_runs")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JudgeJobRun {
    /** 실행 시도 종결 사유이며 사용자 정답 판정과 구분한다. */
    public enum Result {
        SUCCEEDED, SYSTEM_ERROR, LEASE_EXPIRED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(
        fetch = FetchType.LAZY,
        optional = false
    )
    @JoinColumn(
        name = "judge_job_id",
        nullable = false,
        updatable = false
    )
    private JudgeJob judgeJob;
    @Column(
        nullable = false,
        updatable = false
    )
    private int tryCount;
    @Column(
        nullable = false,
        length = 64,
        updatable = false
    )
    private String leaseToken;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(
        nullable = false,
        length = 30,
        updatable = false
    )
    private Result result;
    @Column(
        length = 500,
        updatable = false
    )
    private String errorMessage;
    @Column(
        nullable = false,
        columnDefinition = "DATETIME(6)",
        updatable = false
    )
    private LocalDateTime startedAt;
    @Column(
        nullable = false,
        columnDefinition = "DATETIME(6)",
        updatable = false
    )
    private LocalDateTime finishedAt;
    @Column(
        nullable = false,
        columnDefinition = "DATETIME(6)",
        updatable = false
    )
    private LocalDateTime createdAt;

    /**
     * 실제 종료 시각이 있는 시도만 불변 이력으로 생성한다.
     *
     * @param job 영속 채점 작업
     * @param tryCount 실행 시도 번호
     * @param leaseToken 해당 시도의 임대 토큰
     * @param result 시도 종결 사유
     * @param errorMessage 오류 요약; 성공이면 {@code null}
     * @param startedAt 실제 시도 시작 시각
     * @param finishedAt 실제 시도 종료 시각
     * @param createdAt {@code Clock} 기준 기록 시각
     */
    private JudgeJobRun(
        JudgeJob job,
        int tryCount,
        String leaseToken,
        Result result,
        String errorMessage,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime createdAt
    ) {
        Assert.isTrue(job != null && job.getId() != null, "저장한 채점 작업은 필수입니다.");
        Assert.isTrue(tryCount > 0, "실행 시도 번호는 양수여야 합니다.");
        Assert.hasText(leaseToken, "임대 토큰은 필수입니다.");
        Assert.isTrue(leaseToken.length() <= 64, "임대 토큰은 64자 이하여야 합니다.");
        Assert.notNull(result, "시도 종결 사유는 필수입니다.");
        Assert.isTrue(errorMessage == null || errorMessage.length() <= 500, "오류 요약은 500자 이하여야 합니다.");
        Assert.isTrue(result != Result.SUCCEEDED || errorMessage == null, "성공한 시도에는 오류 요약을 저장하지 않습니다.");
        Assert
            .isTrue(
                startedAt != null && finishedAt != null && !finishedAt.isBefore(startedAt),
                "시도 시작·종료 시각이 올바르지 않습니다."
            );
        Assert.notNull(createdAt, "기록 시각은 필수입니다.");
        judgeJob = job;
        this.tryCount = tryCount;
        this.leaseToken = leaseToken;
        this.result = result;
        this.errorMessage = errorMessage;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.createdAt = createdAt;
    }

    /**
     * 종결한 시도의 이력을 추가한다. 진행 중 시도를 성공으로 추정하지 않는다.
     *
     * @param job 영속 채점 작업
     * @param tryCount 실행 시도 번호
     * @param leaseToken 해당 시도의 임대 토큰
     * @param result 실제 종결 사유
     * @param errorMessage 오류 요약; 성공이면 {@code null}
     * @param startedAt 실제 시작 시각
     * @param finishedAt 실제 종료 시각
     * @param createdAt {@code Clock} 기준 기록 시각
     * @return 저장할 불변 시도 이력
     * @throws IllegalArgumentException 시도 식별자·결과·시각이 올바르지 않은 경우
     */
    public static JudgeJobRun finished(
        JudgeJob job,
        int tryCount,
        String leaseToken,
        Result result,
        String errorMessage,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime createdAt
    ) {
        return new JudgeJobRun(job, tryCount, leaseToken, result, errorMessage, startedAt, finishedAt, createdAt);
    }
}
