package com.backtoback.study.recommendation.repository;

/**
 * 콜드 스타트 추천 조회 결과 행. 네이티브 쿼리 별칭과 getter 이름이 같아야 한다.
 */
public interface PopularStudyRow {

    Long getStudyId();

    String getName();

    Integer getMemberCount();

    Integer getCapacity();

    String getStatus();

    Long getActivityCount();
}
