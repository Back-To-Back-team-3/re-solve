package com.backtoback.problem.problem.domain;

import java.time.LocalDateTime;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.jpa.entity.BaseTimeEntity;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;

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

    /** 문제 행 잠금 아래 작성자와 변경 버전을 확인하고 새 회차의 응답 버전을 확정한다(정책 및 상태 §4). */
    public void prepareNewRevision(Long requestingAdminId, int expectedVersion) {
        if (!authorId.equals(requestingAdminId)) {
            throw new ProblemException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }
        if (visibility == ProblemVisibility.ARCHIVED) {
            throw new ProblemException(ProblemErrorCode.PROBLEM_INVALID_STATE_TRANSITION);
        }
        if (rowVersion != expectedVersion) {
            throw new ProblemException(ProblemErrorCode.PROBLEM_VERSION_CONFLICT);
        }
        rowVersion++;
    }

    /** 사용 회차 또는 통과 언어 변경을 문제 잠금 아래 버전에 반영한다. */
    public void advanceExecutionVersion() {
        rowVersion++;
    }

    /**
     * 문제 잠금 아래 사용 회차가 확인된 문제를 공개한다.
     *
     * @param expectedVersion 원래 기대 버전
     * @param now 잠금 획득 후 공개 시각
     * @throws ProblemException 버전 불일치는 {@code PROBLEM_VERSION_CONFLICT},
     *     공개할 수 없는 용도·상태는 {@code PROBLEM_INVALID_STATE_TRANSITION}
     */
    public void publish(int expectedVersion, LocalDateTime now) {
        if (rowVersion != expectedVersion) {
            throw new ProblemException(ProblemErrorCode.PROBLEM_VERSION_CONFLICT);
        }
        if (scope != ProblemScope.GENERAL || visibility != ProblemVisibility.PRIVATE) {
            throw new ProblemException(ProblemErrorCode.PROBLEM_INVALID_STATE_TRANSITION);
        }
        visibility = ProblemVisibility.PUBLIC;
        rowVersion++;
        if (publishedAt == null) {
            publishedAt = now;
        }
    }

    /**
     * 원래 기대 상태를 확인하고 가드가 준비된 문제의 공개를 중단한다.
     *
     * @param expectedVersion 준비 요청의 원래 버전
     * @param target 비공개 또는 보관 상태
     * @throws ProblemException 버전 경합 또는 허용하지 않는 상태 전이
     */
    public void hide(int expectedVersion, ProblemVisibility target) {
        validateHide(expectedVersion, target);
        visibility = target;
        rowVersion++;
    }

    /**
     * 공개 중단의 상태·버전 조건을 확인한다.
     *
     * @param expectedVersion 기대 버전
     * @param target 비공개 또는 보관
     * @throws ProblemException 버전 경합 또는 허용하지 않는 상태 전이
     */
    public void validateHide(int expectedVersion, ProblemVisibility target) {
        if (rowVersion != expectedVersion) {
            throw new ProblemException(ProblemErrorCode.PROBLEM_VERSION_CONFLICT);
        }
        if (visibility == ProblemVisibility.ARCHIVED || target == ProblemVisibility.PUBLIC
            || (target == ProblemVisibility.PRIVATE && visibility != ProblemVisibility.PUBLIC)) {
            throw new ProblemException(ProblemErrorCode.PROBLEM_INVALID_STATE_TRANSITION);
        }
    }
}
