package com.backtoback.contest.exam.domain;

import java.math.BigDecimal;

import com.backtoback.contest.global.entity.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 시험에 편입한 문제의 고정 회차와 표시 정보를 저장한다.
 */
@Getter
@Entity
@Table(
    name = "exam_problems",
    indexes = @Index(
        name = "idx_exam_problems_problem",
        columnList = "problem_id"
    ),
    uniqueConstraints = @UniqueConstraint(
        name = "uk_exam_problems_exam_problem",
        columnNames = {
            "exam_id",
            "problem_id"
        }
    )
)
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
        nullable = false,
        foreignKey = @ForeignKey(name = "fk_exam_problems_exam")
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
    private Integer revisionNumber;

    @Column(
        nullable = false,
        precision = 6,
        scale = 2
    )
    private BigDecimal score;

    @Column(nullable = false)
    private Integer displayOrder;

    private ExamProblem(
        Exam exam,
        Long problemId,
        Long problemRevisionId,
        String problemTitle,
        Integer revisionNumber,
        BigDecimal score,
        Integer displayOrder
    ) {
        this.exam = exam;
        this.problemId = problemId;
        this.problemRevisionId = problemRevisionId;
        this.problemTitle = problemTitle;
        this.revisionNumber = revisionNumber;
        this.score = score;
        this.displayOrder = displayOrder;
    }

    public static ExamProblem create(
        Exam exam,
        Long problemId,
        Long problemRevisionId,
        String problemTitle,
        Integer revisionNumber,
        BigDecimal score,
        Integer displayOrder
    ) {
        return new ExamProblem(exam, problemId, problemRevisionId, problemTitle, revisionNumber, score, displayOrder);
    }
}
