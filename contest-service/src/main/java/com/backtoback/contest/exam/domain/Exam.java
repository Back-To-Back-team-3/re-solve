package com.backtoback.contest.exam.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.backtoback.contest.global.entity.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 시험 설정과 전체 진행 상태를 저장한다. 외부 서비스의 자원은 ID로만 참조한다.
 */
@Getter
@Entity
@Table(
    name = "exams",
    indexes = {
        @Index(
            name = "idx_exams_study_starts_at",
            columnList = "study_id, starts_at"
        ),
        @Index(
            name = "idx_exams_status_starts_at",
            columnList = "status, starts_at"
        ),
        @Index(
            name = "idx_exams_status_ends_at",
            columnList = "status, ends_at"
        )
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Exam extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long studyId;

    @Column(nullable = false)
    private Long creatorId;

    @Column(
        nullable = false,
        length = 200
    )
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(
        nullable = false,
        length = 30,
        columnDefinition = "VARCHAR(30)"
    )
    private ExamMode mode;

    @Column(nullable = false)
    private LocalDateTime startsAt;

    @Column(nullable = false)
    private LocalDateTime endsAt;

    private Integer durationMinutes;

    @Enumerated(EnumType.STRING)
    @Column(
        nullable = false,
        length = 30,
        columnDefinition = "VARCHAR(30)"
    )
    private ExamStatus status;

    @Column(nullable = false)
    private Integer examRevision;

    @Column(
        nullable = false,
        precision = 6,
        scale = 2
    )
    private BigDecimal totalScore;

    private LocalDateTime closedAt;

    @Column(nullable = false)
    private Long resultRevision;

    private LocalDateTime finalizedAt;

    private LocalDateTime canceledAt;

    @Version
    @Column(nullable = false)
    private Long version;

    private Exam(
        Long studyId,
        Long creatorId,
        String title,
        ExamMode mode,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        Integer durationMinutes,
        BigDecimal totalScore
    ) {
        this.studyId = studyId;
        this.creatorId = creatorId;
        this.title = title;
        this.mode = mode;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.durationMinutes = durationMinutes;
        this.status = ExamStatus.SCHEDULED;
        this.examRevision = 1;
        this.totalScore = totalScore;
        this.resultRevision = 0L;
    }

    /**
     * 저장할 시험 모델을 만든다. 생성 권한과 자원 상태 검증은 서비스에서 처리한다.
     */
    public static Exam create(
        Long studyId,
        Long creatorId,
        String title,
        ExamMode mode,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        Integer durationMinutes,
        BigDecimal totalScore
    ) {
        return new Exam(studyId, creatorId, title, mode, startsAt, endsAt, durationMinutes, totalScore);
    }
}
