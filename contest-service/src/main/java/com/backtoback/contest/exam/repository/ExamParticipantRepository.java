package com.backtoback.contest.exam.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.contest.exam.domain.ExamParticipant;

/**
 * 시험 참가자의 저장·조회에 사용할 기본 JPA 기능을 제공한다.
 * 소속 시험을 먼저 저장하며 취소되지 않은 중복 참가는 DB 유일 제약으로 차단된다.
 * 취소 후 재신청은 기존 참가를 삭제하지 않고 새 참가 객체를 저장하는 서비스 흐름으로 처리해야 한다.
 * <p>기준 문서:
 * <ul>
 * <li>도메인 및 데이터베이스 v1.0 / §4.4.3 시험 참가자 (exam_participants): 참가 저장과 취소 이력 보존 제약</li>
 * </ul>
 */
public interface ExamParticipantRepository extends JpaRepository<ExamParticipant, Long> {

}
