package com.backtoback.study.hint.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.study.hint.domain.HintDailyUsage;

public interface HintDailyUsageRepository extends JpaRepository<HintDailyUsage, Long> {

    // 조회·증감 메서드는 힌트 요청 기능 Issue에서 추가한다.
}
