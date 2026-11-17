package com.backtoback.study.recommendation.domain;

/**
 * 추천 응답 모드 (API 명세서 5.1.4 {@code mode}).
 * <p>{@code PERSONALIZED}는 학습 프로필 기반 규칙 추천, {@code POPULAR}는 최근 활동순 콜드 스타트 추천이다.
 */
public enum RecommendationMode {
    PERSONALIZED, POPULAR
}
