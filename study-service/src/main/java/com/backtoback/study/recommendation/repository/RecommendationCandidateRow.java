package com.backtoback.study.recommendation.repository;

import java.time.LocalDateTime;

/**
 * 규칙 기반 추천 점수 계산 대상 스터디 행. 네이티브 쿼리 별칭과 getter 이름이 같아야 한다.
 */
public interface RecommendationCandidateRow {

    Long getStudyId();

    String getGoal();

    String getSkillLevel();

    String getPreferredLanguage();

    String getActiveTimeSlot();

    LocalDateTime getLastActivityAt();
}
