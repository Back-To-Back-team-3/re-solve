package com.backtoback.member.skill.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

import com.backtoback.common.jpa.entity.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 진단 응시 (ERD 4.1.6). 태그당 진행 중 응시는 {@code uk_diagnosis_attempts_member_active_tag}로 1건만 둔다.
 */
@Getter
@Entity
@Table(name = "diagnosis_attempts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DiagnosisAttempt extends BaseTimeEntity {

    /**
     * 진단 제한 시간 (정책 및 상태 8.3).
     */
    public static final Duration TIME_LIMIT = Duration.ofMinutes(60);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long memberId;

    @Column(nullable = false)
    private Long tagId;

    @Column(length = 200)
    private String tagName;

    @Enumerated(EnumType.STRING)
    @Column(
        nullable = false,
        length = 30
    )
    private DiagnosisStatus status;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    @Column(nullable = false)
    private LocalDateTime endsAt;

    private LocalDateTime closedAt;

    @Column(length = 30)
    private String closeReason;

    private BigDecimal score;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private TagLevel resultLevel;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private TagLevel appliedLevel;

    @Column(
        name = "is_cooldown_exempt",
        nullable = false
    )
    private boolean cooldownExempt;

    private LocalDateTime scoredAt;

    private DiagnosisAttempt(Long memberId, Long tagId, String tagName, LocalDateTime startedAt) {
        this.memberId = memberId;
        this.tagId = tagId;
        this.tagName = tagName;
        this.status = DiagnosisStatus.IN_PROGRESS;
        this.startedAt = startedAt;
        this.endsAt = startedAt.plus(TIME_LIMIT);
    }

    public static DiagnosisAttempt start(Long memberId, Long tagId, String tagName, LocalDateTime startedAt) {
        return new DiagnosisAttempt(memberId, tagId, tagName, startedAt);
    }

    /**
     * 결과를 확정한다 (정책 및 상태 8.3, {@code CLOSED → SCORED}). 호출하는 쪽이 응시 행을 잠그고 {@code CLOSED}인지 확인한다.
     *
     * @param cooldownExempt 채점 장애({@code FAILED})가 있어 재응시 쿨타임을 적용하지 않는지
     */
    public void score(
        BigDecimal score,
        TagLevel resultLevel,
        TagLevel appliedLevel,
        boolean cooldownExempt,
        LocalDateTime now
    ) {
        this.status = DiagnosisStatus.SCORED;
        this.score = score;
        this.resultLevel = resultLevel;
        this.appliedLevel = appliedLevel;
        this.cooldownExempt = cooldownExempt;
        this.scoredAt = now;
    }

    /**
     * 진단을 종료한다 (API 명세서 1.5.6, 스케줄러 1.1). 호출하는 쪽이 응시 행을 잠그고 {@code IN_PROGRESS}인지 확인한다.
     *
     * @param closedAt 쿨타임 기준 시각. 만료 종료는 실행 시각이 아니라 {@code ends_at}이다.
     */
    public void close(String closeReason, LocalDateTime closedAt) {
        this.status = DiagnosisStatus.CLOSED;
        this.closeReason = closeReason;
        this.closedAt = closedAt;
    }

    /**
     * 코드를 저장·제출할 수 있는지. 만료 스케줄러가 닫기 전이라도 제한 시각이 지나면 받지 않는다.
     */
    public boolean isOpenAt(LocalDateTime now) {
        return status == DiagnosisStatus.IN_PROGRESS && !now.isAfter(endsAt);
    }
}
