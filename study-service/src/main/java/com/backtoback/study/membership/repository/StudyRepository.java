package com.backtoback.study.membership.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.study.membership.domain.Study;

public interface StudyRepository extends JpaRepository<Study, Long> {

    // 조회 메서드는 필요한 기능 Issue에서 추가한다.
}
