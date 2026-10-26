package com.backtoback.study.assignment.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.study.assignment.domain.AssignmentCompletion;

public interface AssignmentCompletionRepository extends JpaRepository<AssignmentCompletion, Long> {

    // 조회 메서드는 필요한 기능 Issue에서 추가한다.
}
