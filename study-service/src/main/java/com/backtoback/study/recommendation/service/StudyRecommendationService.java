package com.backtoback.study.recommendation.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.study.recommendation.domain.RecommendationMode;
import com.backtoback.study.recommendation.dto.StudyRecommendationPageResponse;
import com.backtoback.study.recommendation.dto.StudyRecommendationResponse;
import com.backtoback.study.recommendation.repository.StudyRecommendationQueryRepository;
import com.backtoback.study.replica.repository.MemberLearningProfileReplicaRepository;

import lombok.RequiredArgsConstructor;

/**
 * 스터디 추천 조회 (API 명세서 5.1.4, 정책 및 상태 §8.2 스터디 추천).
 * <p>규칙 기반 점수 추천과 추천 결과 저장·갱신은 하지 않고, 최근 활동순 콜드 스타트 결과만 반환한다.
 */
@Service
@RequiredArgsConstructor
public class StudyRecommendationService {

    // 정책 및 상태 §8.2 콜드 스타트 "최근 7일 활동". 조회 시각 기준 7×24시간으로 계산한다.
    private static final long ACTIVITY_WINDOW_DAYS = 7;

    private final StudyRecommendationQueryRepository studyRecommendationQueryRepository;
    private final MemberLearningProfileReplicaRepository memberLearningProfileReplicaRepository;
    private final Clock clock;

    /**
     * 추천 스터디를 최근 활동순({@code POPULAR})으로 반환한다. 학습 프로필 복제본이 없으면
     * {@code isProfileRequired}를 {@code true}로 반환하며 오류로 처리하지 않는다.
     */
    @Transactional(readOnly = true)
    public StudyRecommendationPageResponse getRecommendations(Long memberId, Pageable pageable) {
        LocalDateTime since = LocalDateTime.now(clock).minusDays(ACTIVITY_WINDOW_DAYS);
        Page<StudyRecommendationResponse> result
            = studyRecommendationQueryRepository
                .findPopularStudies(memberId, since, pageable)
                .map(StudyRecommendationResponse::popular);
        boolean isProfileRequired
            = memberLearningProfileReplicaRepository.findAllByMemberIdIn(List.of(memberId)).isEmpty();
        return StudyRecommendationPageResponse.of(RecommendationMode.POPULAR, isProfileRequired, result);
    }
}
