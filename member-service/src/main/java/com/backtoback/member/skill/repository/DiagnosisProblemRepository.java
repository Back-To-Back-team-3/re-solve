package com.backtoback.member.skill.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.member.skill.domain.DiagnosisProblem;

public interface DiagnosisProblemRepository extends JpaRepository<DiagnosisProblem, Long> {

    List<DiagnosisProblem> findAllByDiagnosisAttemptIdOrderByDifficultyAsc(Long diagnosisAttemptId);
}
