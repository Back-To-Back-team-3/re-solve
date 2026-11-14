package com.backtoback.contest.exam.domain;

import java.math.BigDecimal;

import com.backtoback.common.jpa.entity.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 시험에 고정된 문제 회차와 표시 정보를 저장한다 (ERD 4.4.2 exam_problems).
 */
@Getter
@Entity
@Table(name = "exam_problems")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExamProblem extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(
        fetch = FetchType.LAZY,
        optional = false
    )
    @JoinColumn(
        name = "exam_id",
        nullable = false
    )
    private Exam exam;

    @Column(nullable = false)
    private Long problemId;

    @Column(nullable = false)
    private Long problemRevisionId;

    @Column(
        nullable = false,
        length = 200
    )
    private String problemTitle;

    @Column(nullable = false)
    private int revisionNumber;

    @Column(
        nullable = false,
        precision = 6,
        scale = 2
    )
    private BigDecimal score;

    @Column(nullable = false)
    private int displayOrder;

    private ExamProblem(
        Exam exam,
        Long problemId,
        Long problemRevisionId,
        String problemTitle,
        int revisionNumber,
        BigDecimal score,
        int displayOrder
    ) {
        this.exam = exam;
        this.problemId = problemId;
        this.problemRevisionId = problemRevisionId;
        this.problemTitle = problemTitle;
        this.revisionNumber = revisionNumber;
        this.score = score;
        this.displayOrder = displayOrder;
    }

    public static ExamProblem pin(
        Exam exam,
        Long problemId,
        Long problemRevisionId,
        String problemTitle,
        int revisionNumber,
        BigDecimal score,
        int displayOrder
    ) {
        return new ExamProblem(exam, problemId, problemRevisionId, problemTitle, revisionNumber, score, displayOrder);
    }
}
