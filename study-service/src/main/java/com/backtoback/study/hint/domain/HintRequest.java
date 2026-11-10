package com.backtoback.study.hint.domain;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 힌트 요청 애그리게이트(ERD 4.6.1 hint_requests).
 * {@code active_level} 생성 컬럼은 UK {@code uk_hint_requests_member_problem_active_level} 전용이라 매핑하지 않는다.
 * 상태 전이·LLM 연동 메서드는 힌트 요청 기능 Issue에서 추가한다.
 */
@Getter
@Entity
@Table(
    name = "hint_requests",
    indexes = {
        @Index(
            name = "idx_hint_requests_member_problem_level",
            columnList = "member_id, problem_id, level"
        ),
        @Index(
            name = "idx_hint_requests_status_generation_started_at",
            columnList = "status, generation_started_at"
        )
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HintRequest extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    // problem.problem_revisions.id — 요청 시점 공개 회차(캐시 키)
    @Column(
        name = "problem_revision_id",
        nullable = false
    )
    private Long problemRevisionId;

    @Column(nullable = false)
    private int level;

    @Enumerated(EnumType.STRING)
    @Column(
        nullable = false,
        length = 30
    )
    private HintRequestStatus status;

    @Column(
        name = "is_cached",
        nullable = false
    )
    private boolean cached;

    @Column(
        name = "is_fallback",
        nullable = false
    )
    private boolean fallback;

    @Column(
        name = "fallback_reason",
        length = 30
    )
    private String fallbackReason;

    @Column(
        name = "blocked_reason",
        length = 30
    )
    private String blockedReason;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "generation_started_at")
    private LocalDateTime generationStartedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
