package com.backtoback.study.assignment.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.study.assignment.domain.AssignmentProblem;

public interface AssignmentProblemRepository extends JpaRepository<AssignmentProblem, Long> {

    // 조회 메서드는 필요한 기능 Issue에서 추가한다.
}
