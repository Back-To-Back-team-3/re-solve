package com.backtoback.study.membership.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.study.membership.domain.StudyMembership;

public interface StudyMembershipRepository extends JpaRepository<StudyMembership, Long> {

    // 조회 메서드는 필요한 기능 Issue에서 추가한다.
}
