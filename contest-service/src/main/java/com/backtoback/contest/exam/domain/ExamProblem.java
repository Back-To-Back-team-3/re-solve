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
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 시험에 편입한 문제의 고정 회차와 표시 정보를 저장한다.
 * Builder는 create의 입력만 받으며 DB 식별자와 감사 시간은 저장 과정에서 설정된다.
 * <p>편입 시 전달받은 회차 ID·제목·표시용 회차 번호를 보존하므로
 * 원본 문제의 이후 수정과 별개로 시험에서 사용할 회차를 식별할 수 있다.
 * 문제 서비스 자원은 ID로만 참조하며, 소속 시험만 내부 FK와 지연 로딩 관계로 연결한다.
 * cascade 저장·삭제는 사용하지 않으므로 시험 저장과 문제 저장은 호출자가 명시적으로 수행한다.
 * <p>기준 문서:
 * <ul>
 * <li>도메인 및 데이터베이스 v1.0 / §4.4.2 시험 문제 (exam_problems): 고정 회차·표시 정보·시험별 문제 유일 제약</li>
 * </ul>
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

    // 저장 전에는 null이며 INSERT 시 DB가 식별자를 생성한다.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false)
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

    // 시험에 사용할 회차의 외부 참조 ID이며 문제의 최신 회차를 자동으로 따라가지 않는다.
    @Column(nullable = false)
    private Long problemRevisionId;

    // 선택한 문제 회차의 제목 스냅샷이며 원본 문제의 제목 변경을 자동 반영하지 않는다.
    @Column(
        nullable = false,
        length = 200
    )
    private String problemTitle;

    // 회차의 DB 식별자인 problemRevisionId와 구분하는 표시용 번호다.
    @Column(nullable = false)
    private Integer revisionNumber;

    // 이 문제의 시험 배점(점)이며 참가자가 획득한 점수와 구분한다.
    @Column(
        nullable = false,
        precision = 6,
        scale = 2
    )
    private BigDecimal score;

    // 1부터 연속하는 시험 안의 표시 순서다. 연속성 검증은 편입을 담당하는 서비스의 책임이다.
    @Column(nullable = false)
    private Integer displayOrder;

    /**
     * 호출자가 선택한 문제 회차와 표시 정보를 그대로 저장 모델에 옮긴다.
     *
     * @param exam 소속 시험
     * @param problemId 외부 문제 ID
     * @param problemRevisionId 고정할 외부 회차 ID
     * @param problemTitle 편입 시 문제 제목
     * @param revisionNumber 표시용 회차 번호
     * @param score 문제 배점
     * @param displayOrder 시험 안의 표시 순서
     */
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

    /**
     * 편입할 문제의 미저장 스냅샷을 만든다.
     * Builder의 build도 이 메서드를 호출하며 입력 이름으로 회차·배점·표시 순서를 구분할 수 있다.
     * Builder에서 생략한 입력은 null로 전달되며, 이 메서드는 필수 입력을 자동 검증하지 않는다.
     * 공개 상태·회차 유효성·배점·표시 순서 검증은 호출하는 서비스가 수행한다.
     * 같은 시험의 중복 문제는 저장 시 DB 유일 제약으로도 차단된다.
     *
     * @param exam 먼저 저장할 소속 시험
     * @param problemId 문제 서비스에서 확인한 문제 ID
     * @param problemRevisionId 시험에서 사용할 고정 회차 ID
     * @param problemTitle 선택한 회차의 문제 제목 스냅샷
     * @param revisionNumber 선택한 회차의 표시용 번호 스냅샷
     * @param score 해당 문제의 시험 배점
     * @param displayOrder 1부터 연속하도록 호출자가 결정한 표시 순서
     * @return 전달한 고정 회차·표시 정보·배점을 가진 미저장 시험 문제 객체
     */
    @Builder
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
