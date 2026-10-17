package com.backtoback.contest.exam.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.contest.exam.domain.Exam;

/**
 * 시험 저장·조회에 사용할 기본 JPA 기능을 제공한다.
 * 권한·상태 전이·이벤트 발행은 이 Repository에서 처리하지 않는다.
 * 변경의 트랜잭션 범위는 호출하는 서비스에서 정하며 업무 순번과 낙관적 락 버전은 구분한다.
 * <p>기준 문서:
 * <ul>
 * <li>도메인 및 데이터베이스 v1.0 / §4.4.1 시험 (exams): 시험 저장 모델과 기본 조회 대상</li>
 * </ul>
 */
public interface ExamRepository extends JpaRepository<Exam, Long> {

}
