package com.backtoback.contest.exam.domain;

import java.math.BigDecimal;
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
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 스터디 시험의 설정과 결과 상태를 저장한다 (ERD 4.4.1 exams).
 */
@Getter
@Entity
@Table(name = "exams")
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
        length = 30
    )
    private ExamMode mode;

    @Column(
        nullable = false,
        columnDefinition = "DATETIME(6)"
    )
    private LocalDateTime startsAt;

    @Column(
        nullable = false,
        columnDefinition = "DATETIME(6)"
    )
    private LocalDateTime endsAt;

    private Integer durationMinutes;

    @Enumerated(EnumType.STRING)
    @Column(
        nullable = false,
        length = 30
    )
    private ExamStatus status;

    @Column(nullable = false)
    private int examRevision;

    @Column(
        nullable = false,
        precision = 6,
        scale = 2
    )
    private BigDecimal totalScore;

    @Column(columnDefinition = "DATETIME(6)")
    private LocalDateTime closedAt;

    @Column(nullable = false)
    private long resultRevision;

    @Column(columnDefinition = "DATETIME(6)")
    private LocalDateTime finalizedAt;

    @Column(columnDefinition = "DATETIME(6)")
    private LocalDateTime canceledAt;

    @Version
    @Column(nullable = false)
    private long version;

    private Exam(
        Long studyId,
        Long creatorId,
        String title,
        ExamMode mode,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        Integer durationMinutes,
        int examRevision,
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
        this.examRevision = examRevision;
        this.totalScore = totalScore;
        this.resultRevision = 0;
    }

    /**
     * 배점 합계를 지정하지 않으면 기본값 100을 사용한다 (ERD 4.4.1 exams).
     */
    public static Exam create(
        Long studyId,
        Long creatorId,
        String title,
        ExamMode mode,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        Integer durationMinutes,
        int examRevision
    ) {
        return new Exam(
            studyId,
            creatorId,
            title,
            mode,
            startsAt,
            endsAt,
            durationMinutes,
            examRevision,
            new BigDecimal("100.00")
        );
    }

    public static Exam create(
        Long studyId,
        Long creatorId,
        String title,
        ExamMode mode,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        Integer durationMinutes,
        int examRevision,
        BigDecimal totalScore
    ) {
        return new Exam(studyId, creatorId, title, mode, startsAt, endsAt, durationMinutes, examRevision, totalScore);
    }

    /** 시작 전 검증을 마친 전체 설정을 교체하고 이벤트 순서를 증가시킨다. */
    public void updateSettings(String title, LocalDateTime startsAt, LocalDateTime endsAt, BigDecimal totalScore) {
        this.title = title;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.totalScore = totalScore;
        this.examRevision = Math.incrementExact(this.examRevision);
    }

    /** 시작 조건을 확인한 시험을 진행 상태로 바꾼다. */
    public void start() {
        if (status != ExamStatus.SCHEDULED) {
            throw new IllegalStateException("Exam is not scheduled");
        }
        status = ExamStatus.IN_PROGRESS;
    }

    /** 전체 마감이 지난 진행 시험의 종료 시각을 기록한다. */
    public void close(LocalDateTime now) {
        if (status != ExamStatus.IN_PROGRESS) {
            throw new IllegalStateException("Exam is not in progress");
        }
        status = ExamStatus.CLOSED;
        closedAt = now;
    }

    /** 시작 전 시험을 취소하고 처리 시각을 보관한다. */
    public void cancel(LocalDateTime now) {
        if (status != ExamStatus.SCHEDULED) {
            throw new IllegalStateException("Exam is not scheduled");
        }
        status = ExamStatus.CANCELED;
        canceledAt = now;
    }
}
