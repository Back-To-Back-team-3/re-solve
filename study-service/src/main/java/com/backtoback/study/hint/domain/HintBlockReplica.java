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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 힌트 차단 복제본(ERD 4.6.3 hint_block_replicas).
 * 시험·대회·진단 이벤트를 소비하여 유지한다.
 * 차단 판정: {@code starts_at <= now() AND now() < ends_at + INTERVAL 10 MINUTE}.
 */
@Getter
@Entity
@Table(
    name = "hint_block_replicas",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_hint_block_replicas_member_context_problem",
            columnNames = {
                "member_id",
                "context_type",
                "context_id",
                "problem_id"
            }
        )
    },
    indexes = {
        @Index(
            name = "idx_hint_block_replicas_member_problem_ends_at",
            columnList = "member_id, problem_id, ends_at"
        ),
        @Index(
            name = "idx_hint_block_replicas_context",
            columnList = "context_type, context_id"
        ),
        @Index(
            name = "idx_hint_block_replicas_ends_at",
            columnList = "ends_at"
        )
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HintBlockReplica extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
        name = "member_id",
        nullable = false
    )
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(
        name = "context_type",
        nullable = false,
        length = 30
    )
    private HintBlockContextType contextType;

    @Column(
        name = "context_id",
        nullable = false
    )
    private Long contextId;

    @Column(
        name = "problem_id",
        nullable = false
    )
    private Long problemId;

    @Column(
        name = "starts_at",
        nullable = false
    )
    private LocalDateTime startsAt;

    @Column(
        name = "ends_at",
        nullable = false
    )
    private LocalDateTime endsAt;

    @Column(
        name = "context_revision",
        nullable = false
    )
    private int contextRevision;

    private HintBlockReplica(
        Long memberId,
        HintBlockContextType contextType,
        Long contextId,
        Long problemId,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        int contextRevision
    ) {
        this.memberId = memberId;
        this.contextType = contextType;
        this.contextId = contextId;
        this.problemId = problemId;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.contextRevision = contextRevision;
    }

    /**
     * 이벤트 수신 시 차단 복제본 행을 생성한다(ERD 4.6.3).
     * INSERT IGNORE로 멱등 삽입하므로 UK 충돌 시 무시된다.
     */
    public static HintBlockReplica of(
        Long memberId,
        HintBlockContextType contextType,
        Long contextId,
        Long problemId,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        int contextRevision
    ) {
        return new HintBlockReplica(memberId, contextType, contextId, problemId, startsAt, endsAt, contextRevision);
    }
}
