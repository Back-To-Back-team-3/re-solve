package com.backtoback.study.assignment.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.study.assignment.domain.Assignment;
import com.backtoback.study.assignment.domain.AssignmentStatus;

public interface AssignmentRepository extends JpaRepository<Assignment, Long> {

    List<Assignment> findByStudyIdAndStatusIn(Long studyId, List<AssignmentStatus> statuses);
}
