package com.backtoback.problem.problem.domain;

import java.time.LocalDateTime;

import com.backtoback.problem.global.entity.BaseEntity;

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
 * 문제 애그리게이트(ERD 4.2.1 problems). 공개·보관 전환 등 상태 전이는 해당 기능 Issue에서 엔티티 메서드로 추가한다.
 */
@Entity
@Table(
    name = "problems",
    indexes = {
        @Index(
            name = "idx_problems_scope_visibility_created_at",
            columnList = "scope, visibility, created_at"
        )
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Problem extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
        name = "author_id",
        nullable = false
    )
    private Long authorId;

    @Enumerated(EnumType.STRING)
    @Column(
        nullable = false,
        length = 30
    )
    private ProblemScope scope;

    @Enumerated(EnumType.STRING)
    @Column(
        nullable = false,
        length = 30
    )
    private ProblemVisibility visibility;

    @Column(name = "contest_id")
    private Long contestId;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    private Problem(Long authorId, ProblemScope scope) {
        this.authorId = authorId;
        this.scope = scope;
        this.visibility = ProblemVisibility.PRIVATE;
    }

    /**
     * 비공개 상태의 새 문제를 만든다. 대회 전용 문제의 {@code contestId} 연결은 해당 기능 Issue에서 추가한다.
     */
    public static Problem create(Long authorId, ProblemScope scope) {
        return new Problem(authorId, scope);
    }
}
