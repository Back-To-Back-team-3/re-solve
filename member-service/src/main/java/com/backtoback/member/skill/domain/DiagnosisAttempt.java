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

    @Column(
        name = "is_cooldown_exempt",
        nullable = false
    )
    private boolean cooldownExempt;

    private LocalDateTime scoredAt;

    private DiagnosisAttempt(Long memberId, Long tagId, LocalDateTime startedAt) {
        this.memberId = memberId;
        this.tagId = tagId;
        this.status = DiagnosisStatus.IN_PROGRESS;
        this.startedAt = startedAt;
        this.endsAt = startedAt.plus(TIME_LIMIT);
    }

    public static DiagnosisAttempt start(Long memberId, Long tagId, LocalDateTime startedAt) {
        return new DiagnosisAttempt(memberId, tagId, startedAt);
    }

    /**
     * 코드를 저장·제출할 수 있는지. 만료 스케줄러가 닫기 전이라도 제한 시각이 지나면 받지 않는다.
     */
    public boolean isOpenAt(LocalDateTime now) {
        return status == DiagnosisStatus.IN_PROGRESS && !now.isAfter(endsAt);
    }
}
