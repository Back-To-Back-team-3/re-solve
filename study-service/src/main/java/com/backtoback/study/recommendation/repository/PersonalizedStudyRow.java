package com.backtoback.study.recommendation.repository;

import java.math.BigDecimal;

/**
 * 저장된 개인화 추천 결과 조회 행. 네이티브 쿼리 별칭과 getter 이름이 같아야 한다.
 */
public interface PersonalizedStudyRow {

    Long getStudyId();

    String getName();

    BigDecimal getScore();

    /** 추천 근거 문구 배열의 JSON 원문 (ERD 4.5.11 {@code reasons_json}). */
    String getReasonsJson();

    Integer getMemberCount();

    Integer getCapacity();

    String getStatus();
}
