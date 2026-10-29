package com.backtoback.problem.problem.domain;

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
 * 문제 애그리게이트(ERD 4.2.1 problems). 동시 수정 충돌은 JPA 낙관적 락이 아니라 관리자·P1과 공유하는 애플리케이션
 * 관리 버전({@code rowVersion})으로 관리하므로 {@code BaseTimeEntity}만 상속한다. 공개·보관 전환, 대회 연결 등
 * 상태 전이는 해당 기능 Issue에서 엔티티 메서드로 추가한다.
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
public class Problem extends BaseTimeEntity {
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

    @Column(name = "contest_problem_revision_id")
    private Long contestProblemRevisionId;

    @Column(name = "contest_link_source_version")
    private Integer contestLinkSourceVersion;

    @Column(name = "contest_policy_actor_id")
    private Long contestPolicyActorId;

    @Column(
        name = "row_version",
        nullable = false
    )
    private int rowVersion;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    private Problem(Long authorId, ProblemScope scope) {
        this.authorId = authorId;
        this.scope = scope;
        this.visibility = ProblemVisibility.PRIVATE;
        this.rowVersion = 1;
    }

    /**
     * 비공개 상태의 새 문제를 만든다. {@code rowVersion}은 애플리케이션 초기값 1로 시작한다. 대회 전용 문제의
     * {@code contestId} 연결은 해당 기능 Issue에서 추가한다.
     */
    public static Problem create(Long authorId, ProblemScope scope) {
        return new Problem(authorId, scope);
    }
}
