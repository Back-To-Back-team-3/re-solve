package com.backtoback.contest.exam.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.contest.exam.domain.ExamProblem;

/**
 * 시험에 편입된 고정 문제의 저장·조회에 사용할 기본 JPA 기능을 제공한다.
 * 소속 시험을 먼저 저장해야 하며 같은 시험의 중복 문제는 DB 유일 제약으로 차단된다.
 * 외부 문제·회차 조회나 시험의 cascade 저장·삭제는 수행하지 않는다.
 * <p>기준 문서:
 * <ul>
 * <li>도메인 및 데이터베이스 v1.0 / §4.4.2 시험 문제 (exam_problems): 고정 문제의 내부 FK와 유일 제약</li>
 * </ul>
 */
public interface ExamProblemRepository extends JpaRepository<ExamProblem, Long> {

}
