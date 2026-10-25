package com.backtoback.study.membership.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.study.membership.domain.StudyMembershipHistory;

public interface StudyMembershipHistoryRepository extends JpaRepository<StudyMembershipHistory, Long> {

    // 조회 메서드는 필요한 기능 Issue에서 추가한다.
}
