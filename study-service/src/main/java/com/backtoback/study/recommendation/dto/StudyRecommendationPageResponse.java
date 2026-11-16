package com.backtoback.study.recommendation.dto;

import java.util.List;

import org.springframework.data.domain.Page;

import com.backtoback.study.recommendation.domain.RecommendationMode;

/**
 * 추천 스터디 조회 응답 (API 명세서 5.1.4). 추천 모드·프로필 입력 필요 여부와 페이지 정보를 같은 단계에 둔다.
 */
public record StudyRecommendationPageResponse(
    RecommendationMode mode,
    boolean isProfileRequired,
    List<StudyRecommendationResponse> content,
    int page,
    int size,
    long totalElements,
    int totalPages,
    boolean hasNext
) {

    public static StudyRecommendationPageResponse of(
        RecommendationMode mode,
        boolean isProfileRequired,
        Page<StudyRecommendationResponse> result
    ) {
        return new StudyRecommendationPageResponse(
            mode,
            isProfileRequired,
            result.getContent(),
            result.getNumber(),
            result.getSize(),
            result.getTotalElements(),
            result.getTotalPages(),
            result.hasNext()
        );
    }
}
