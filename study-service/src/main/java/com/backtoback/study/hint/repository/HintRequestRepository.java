package com.backtoback.study.hint.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.study.hint.domain.HintRequest;

public interface HintRequestRepository extends JpaRepository<HintRequest, Long> {

    // 조회·상태 전이 메서드는 힌트 요청 기능 Issue에서 추가한다.
}
