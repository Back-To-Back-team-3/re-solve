package com.backtoback.member.skill.domain;

import com.backtoback.common.jpa.entity.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 진단 문항 (ERD 4.1.7). 출제 시점의 회차와 난이도를 고정한다. 응시당 난이도별 1문항이다.
 */
@Getter
@Entity
@Table(name = "diagnosis_problems")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DiagnosisProblem extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long diagnosisAttemptId;

    @Column(nullable = false)
    private Long problemId;

    @Column(nullable = false)
    private Long problemRevisionId;

    @Column(nullable = false)
    private int difficulty;

    @Column(
        name = "is_accepted",
        nullable = false
    )
    private boolean accepted;

    private Long finalSubmissionId;

    @Column(length = 30)
    private String finalVerdict;

    /**
     * 채점 결과를 반영한다. 제한 시간 안에 한 번이라도 AC면 정답으로 보고(레벨 판정), 문항의 마지막 제출이면 최종 판정으로 남긴다.
     */
    public void applyResult(Long submissionId, String verdict, boolean latest) {
        if ("AC".equals(verdict)) {
            this.accepted = true;
        }
        if (latest) {
            this.finalSubmissionId = submissionId;
            this.finalVerdict = verdict;
        }
    }

    public DiagnosisProblem(Long diagnosisAttemptId, Long problemId, Long problemRevisionId, int difficulty) {
        this.diagnosisAttemptId = diagnosisAttemptId;
        this.problemId = problemId;
        this.problemRevisionId = problemRevisionId;
        this.difficulty = difficulty;
    }
}
