package com.backtoback.study.recommendation.dto;

import java.math.BigDecimal;
import java.util.List;

import com.backtoback.study.membership.domain.StudyStatus;
import com.backtoback.study.recommendation.repository.PopularStudyRow;

/**
 * 추천 스터디 항목 응답 (API 명세서 5.1.4 {@code content}).
 */
public record StudyRecommendationResponse(
    String studyId,
    String name,
    BigDecimal score,
    List<String> reasons,
    int memberCount,
    int capacity,
    StudyStatus status
) {

    // POPULAR 모드는 점수 없이 규칙 추천과 구분되는 근거만 보여준다(API 명세서 5.1.4, 정책 및 상태 §8.2 콜드 스타트).
    private static final List<String> POPULAR_REASONS = List.of("최근 활동이 많은 스터디");

    /**
     * 콜드 스타트 조회 행을 {@code POPULAR} 항목으로 바꾼다. {@code score}는 {@code null}이다.
     */
    public static StudyRecommendationResponse from(PopularStudyRow row) {
        return new StudyRecommendationResponse(
            String.valueOf(row.getStudyId()),
            row.getName(),
            null,
            POPULAR_REASONS,
            row.getMemberCount(),
            row.getCapacity(),
            StudyStatus.valueOf(row.getStatus())
        );
    }
}
