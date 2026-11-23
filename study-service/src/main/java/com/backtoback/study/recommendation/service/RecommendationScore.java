package com.backtoback.study.recommendation.service;

import java.util.List;

/**
 * 스터디 한 개의 규칙 기반 추천 점수와 추천 근거.
 *
 * @param score 0~100 점수
 * @param reasons 가중치가 큰 항목부터 담은 추천 근거 문구
 */
public record RecommendationScore(int score, List<String> reasons) {
}
