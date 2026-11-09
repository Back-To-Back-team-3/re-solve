package com.backtoback.contest.exam.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.contest.exam.domain.ExamParticipantHistory;

/** 참가 이력을 불변 행으로 저장한다 (ERD 4.4.4). */
public interface ExamParticipantHistoryRepository extends JpaRepository<ExamParticipantHistory, Long> {

}
