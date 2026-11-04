package com.backtoback.contest.exam.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.contest.exam.domain.Exam;

/**
 * 시험을 저장하고 조회한다.
 */
public interface ExamRepository extends JpaRepository<Exam, Long> {
    /** 마지막 ID 이후의 시험을 스터디별로 조회한다. (API 명세서 §4.7.5) */
    List<Exam> findByStudyIdAndIdGreaterThanOrderByIdAsc(Long studyId, Long id, Pageable pageable);
}
