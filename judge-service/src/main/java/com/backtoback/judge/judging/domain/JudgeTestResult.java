package com.backtoback.judge.judging.domain;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.util.Assert;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 회차 안의 테스트별 {@code token}과 결과 자리를 저장한다. 숨김 원문은 보관하지 않는다. */
@Entity
@Table(name = "judge_test_results")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JudgeTestResult extends BaseTimeEntity {
    /** 외부 실행 생성·조회 단계다. */
    public enum TokenStatus {
        NOT_CREATED, CREATED, UNKNOWN, DONE
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
    private long testAssetId;
    @Column(
        nullable = false,
        updatable = false
    )
    private int testOrder;
    @Column(
        nullable = false,
        updatable = false
    )
    private boolean isSample;
    @Column(length = 64)
    private String judge0Token;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(
        nullable = false,
        length = 30
    )
    private TokenStatus tokenStatus;
    private Integer createdTryCount;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 30)
    private JudgeVerdict verdict;
    private Integer timeMs;
    private Integer memoryKb;
    @Column(columnDefinition = "MEDIUMTEXT")
    private String actualOutput;

    /**
     * 확정한 회차 테스트의 실행 전 결과 자리를 만든다.
     *
     * @param job 저장한 채점 작업
     * @param testAssetId 외부 테스트 파일 ID
     * @param testOrder 테스트 순번
     * @param sample 공개 테스트 여부
     */
    private JudgeTestResult(JudgeJob job, long testAssetId, int testOrder, boolean sample) {
        Assert.notNull(job, "채점 작업은 필수입니다.");
        Assert.notNull(job.getId(), "저장한 작업만 테스트 결과를 만들 수 있습니다.");
        Assert.isTrue(testAssetId > 0 && testOrder > 0, "테스트 ID와 순번은 양수여야 합니다.");
        judgeJob = job;
        this.testAssetId = testAssetId;
        this.testOrder = testOrder;
        isSample = sample;
        tokenStatus = TokenStatus.NOT_CREATED;
    }

    /**
     * 실행 전에는 {@code token}·판정·측정값·반환값을 비운다.
     *
     * @param job 영속 채점 작업
     * @param testAssetId 고정 회차의 테스트 파일 ID
     * @param testOrder 회차 안 테스트 순번
     * @param sample 공개 테스트 여부
     * @return 실행 전 결과 자리
     * @throws IllegalArgumentException 작업이나 테스트 식별자가 올바르지 않은 경우
     */
    public static JudgeTestResult pending(JudgeJob job, long testAssetId, int testOrder, boolean sample) {
        return new JudgeTestResult(job, testAssetId, testOrder, sample);
    }
}
