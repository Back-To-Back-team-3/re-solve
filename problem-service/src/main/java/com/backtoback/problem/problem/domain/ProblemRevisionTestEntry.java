package com.backtoback.problem.problem.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회차별 테스트 구성(ERD 4.2.7 problem_revision_tests, {@code [IMMUTABLE]}). 문제 수정 회차에 포함된 테스트
 * 파일과 표시 순서·공개 여부를 기록한다. 생성 시각만 있는 불변 테이블이라 {@code BaseTimeEntity}를 상속하지 않고,
 * 기록 시점에 호출자가 시각을 전달한다(공통 모듈 적용 가이드 §2.3 별도 시간 처리). 회차마다 공개·숨김 테스트가 각각
 * 1개 이상이어야 하는 집계 검증은 이 엔티티가 아니라 호출하는 서비스 계층이 수행한다.
 * 클래스명은 {@code ProblemRevisionTest}가 아니라 {@code ProblemRevisionTestEntry}다. 동일 패키지의
 * {@code ProblemRevision} 단위 테스트 클래스({@code ProblemRevisionTest})와 이름이 충돌하기 때문이다.
 */
@Getter
@Entity
@Table(
    name = "problem_revision_tests",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_problem_revision_tests_revision_test_asset",
            columnNames = {
                "problem_revision_id",
                "test_asset_id"
            }
        ),
        @UniqueConstraint(
            name = "uk_problem_revision_tests_revision_order",
            columnNames = {
                "problem_revision_id",
                "test_order"
            }
        )
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProblemRevisionTestEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
        name = "problem_revision_id",
        nullable = false
    )
    private Long problemRevisionId;

    @Column(
        name = "test_asset_id",
        nullable = false
    )
    private Long testAssetId;

    @Column(
        name = "test_order",
        nullable = false
    )
    private int testOrder;

    @Column(
        name = "is_sample",
        nullable = false
    )
    private boolean isSample;

    @Column(
        name = "created_at",
        nullable = false
    )
    private LocalDateTime createdAt;

    private ProblemRevisionTestEntry(
        Long problemRevisionId,
        Long testAssetId,
        int testOrder,
        boolean isSample,
        LocalDateTime createdAt
    ) {
        this.problemRevisionId = problemRevisionId;
        this.testAssetId = testAssetId;
        this.testOrder = testOrder;
        this.isSample = isSample;
        this.createdAt = createdAt;
    }

    /**
     * 회차에 테스트 파일 1건을 구성 순서·공개 여부와 함께 기록한다(ERD 4.2.7). 회차마다 공개·숨김 테스트가 각각
     * 1개 이상인지 확인하는 집계 검증은 호출하는 서비스 계층이 수행한다.
     */
    public static ProblemRevisionTestEntry create(
        Long problemRevisionId,
        Long testAssetId,
        int testOrder,
        boolean isSample,
        LocalDateTime createdAt
    ) {
        return new ProblemRevisionTestEntry(problemRevisionId, testAssetId, testOrder, isSample, createdAt);
    }
}
