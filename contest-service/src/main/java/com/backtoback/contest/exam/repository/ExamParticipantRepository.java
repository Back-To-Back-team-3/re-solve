package com.backtoback.contest.exam.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.contest.exam.domain.ExamParticipant;

public interface ExamParticipantRepository extends JpaRepository<ExamParticipant, Long> {

}
