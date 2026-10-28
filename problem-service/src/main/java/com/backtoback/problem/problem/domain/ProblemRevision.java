package com.backtoback.problem.problem.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 문제 수정 회차(ERD 4.2.2 problem_revisions, {@code [IMMUTABLE]}). 공개된 회차는 수정하지 않고, 수정은
 * 새 회차 생성으로만 반영한다. 식별·고정은 {@code id}({@code problemRevisionId}), 화면 표시는
 * {@code revisionNumber}를 쓴다. 생성 시각만 있는 불변 테이블이라 {@code BaseTimeEntity}를 상속하지 않고,
 * 기록 시점에 호출자가 시각을 전달한다(공통 모듈 적용 가이드 §2.3 별도 시간 처리).
 */
@Getter
@Entity
@Table(
    name = "problem_revisions",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_problem_revisions_problem_revision_number",
        columnNames = {
            "problem_id",
            "revision_number"
        }
    ),
    indexes = {
        @Index(
            name = "idx_problem_revisions_difficulty",
            columnList = "difficulty"
        )
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProblemRevision {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
        name = "problem_id",
        nullable = false
    )
    private Long problemId;

    @Column(
        name = "revision_number",
        nullable = false
    )
    private int revisionNumber;

    @Column(
        name = "body_asset_id",
        nullable = false
    )
    private Long bodyAssetId;

    @Column(
        nullable = false,
        length = 200
    )
    private String title;

    @Column(nullable = false)
    private int difficulty;

    @Column(
        name = "function_spec",
        nullable = false,
        columnDefinition = "json"
    )
    private String functionSpecJson;

    @Column(
        name = "time_limit_ms",
        nullable = false
    )
    private int timeLimitMs;

    @Column(
        name = "memory_limit_kb",
        nullable = false
    )
    private int memoryLimitKb;

    @Column(
        name = "wall_time_limit_ms",
        nullable = false
    )
    private int wallTimeLimitMs;

    @Column(
        name = "output_limit_kb",
        nullable = false
    )
    private int outputLimitKb;

    @Column(
        name = "author_id",
        nullable = false
    )
    private Long authorId;

    @Column(
        name = "created_at",
        nullable = false
    )
    private LocalDateTime createdAt;

    private ProblemRevision(
        Long problemId,
        int revisionNumber,
        Long bodyAssetId,
        String title,
        int difficulty,
        String functionSpecJson,
        int timeLimitMs,
        int memoryLimitKb,
        int wallTimeLimitMs,
        int outputLimitKb,
        Long authorId,
        LocalDateTime createdAt
    ) {
        this.problemId = problemId;
        this.revisionNumber = revisionNumber;
        this.bodyAssetId = bodyAssetId;
        this.title = title;
        this.difficulty = difficulty;
        this.functionSpecJson = functionSpecJson;
        this.timeLimitMs = timeLimitMs;
        this.memoryLimitKb = memoryLimitKb;
        this.wallTimeLimitMs = wallTimeLimitMs;
        this.outputLimitKb = outputLimitKb;
        this.authorId = authorId;
        this.createdAt = createdAt;
    }

    /**
     * 새 문제 수정 회차 1건을 기록한다. 공개된 회차의 수정은 이 팩토리로 새 회차를 만드는 방식으로만 반영한다(ERD 4.2.2).
     */
    public static ProblemRevision create(
        Long problemId,
        int revisionNumber,
        Long bodyAssetId,
        String title,
        int difficulty,
        String functionSpecJson,
        int timeLimitMs,
        int memoryLimitKb,
        int wallTimeLimitMs,
        int outputLimitKb,
        Long authorId,
        LocalDateTime createdAt
    ) {
        return new ProblemRevision(
            problemId,
            revisionNumber,
            bodyAssetId,
            title,
            difficulty,
            functionSpecJson,
            timeLimitMs,
            memoryLimitKb,
            wallTimeLimitMs,
            outputLimitKb,
            authorId,
            createdAt
        );
    }
}
