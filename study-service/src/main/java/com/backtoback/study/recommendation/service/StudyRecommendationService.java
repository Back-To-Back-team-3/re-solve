package com.backtoback.study.recommendation.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import com.backtoback.study.recommendation.domain.RecommendationMode;
import com.backtoback.study.recommendation.dto.StudyRecommendationPageResponse;
import com.backtoback.study.recommendation.dto.StudyRecommendationResponse;
import com.backtoback.study.recommendation.repository.PersonalizedStudyRow;
import com.backtoback.study.recommendation.repository.StudyRecommendationQueryRepository;
import com.backtoback.study.recommendation.repository.StudyRecommendationRepository;
import com.backtoback.study.replica.domain.MemberLearningProfileReplica;
import com.backtoback.study.replica.repository.MemberLearningProfileReplicaRepository;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

/**
 * 스터디 추천 조회 (API 명세서 5.1.4, 정책 및 상태 §8.2 스터디 추천).
 * <p>학습 프로필 입력이 있으면 저장된 규칙 기반 추천 결과({@code PERSONALIZED})를, 없거나 결과가 비면 최근 활동순 결과
 * ({@code POPULAR})를 반환한다. 점수 계산과 결과 저장은 {@link StudyRecommendationRefreshService}가 맡는다.
 */
@Service
@RequiredArgsConstructor
public class StudyRecommendationService {

    private static final Logger log = LoggerFactory.getLogger(StudyRecommendationService.class);

    // 정책 및 상태 §8.2 콜드 스타트 "최근 7일 활동". 조회 시각 기준 7×24시간으로 계산한다.
    private static final long ACTIVITY_WINDOW_DAYS = 7;

    // API 명세서 5.1.4 "추천 결과가 하루보다 오래됐으면 즉시 재계산". 정확히 하루가 지난 결과는 재계산하지 않는다.
    private static final Duration STALE_AFTER = Duration.ofHours(24);

    private final StudyRecommendationQueryRepository studyRecommendationQueryRepository;
    private final StudyRecommendationRepository studyRecommendationRepository;
    private final MemberLearningProfileReplicaRepository memberLearningProfileReplicaRepository;
    private final StudyRecommendationRefreshService studyRecommendationRefreshService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * 추천 스터디를 반환한다. 학습 프로필 입력이 없으면 {@code isProfileRequired}를 {@code true}로 반환하며 오류로 처리하지 않는다.
     * 저장된 추천 결과가 하루보다 오래됐으면 응답 전에 다시 계산하고, 다시 계산하지 못하면 기존 결과로 응답한다.
     */
    // 재계산은 자체 트랜잭션에서 결과를 교체해야 하는데, 읽기 전용 트랜잭션에 합류하면 쓰기가 실패하므로 트랜잭션을 걸지 않는다.
    // 각 조회는 저장소의 기본 읽기 전용 트랜잭션으로 실행된다.
    public StudyRecommendationPageResponse getRecommendations(Long memberId, Pageable pageable) {
        boolean hasProfileInput
            = memberLearningProfileReplicaRepository
                .findAllByMemberIdIn(List.of(memberId))
                .stream()
                .anyMatch(MemberLearningProfileReplica::hasProfileInput);
        if (!hasProfileInput) {
            return popular(memberId, true, pageable);
        }

        // 저장 행이 없으면 학습 프로필 변경 이벤트·매일 갱신에 맡기고 조회 때마다 전체 후보를 다시 계산하지 않는다.
        Optional<LocalDateTime> latestGeneratedAt = studyRecommendationRepository.findLatestGeneratedAt(memberId);
        if (latestGeneratedAt.isEmpty()) {
            return popular(memberId, false, pageable);
        }
        if (latestGeneratedAt.get().isBefore(LocalDateTime.now(clock).minus(STALE_AFTER))) {
            refreshQuietly(memberId);
        }

        Page<PersonalizedStudyRow> rows
            = studyRecommendationQueryRepository.findPersonalizedStudies(memberId, pageable);
        // 요청한 페이지가 아니라 전체 결과가 비었을 때만 바꾼다. 마지막 페이지 뒤를 요청해도 모드가 바뀌지 않게 한다.
        if (rows.getTotalElements() == 0) {
            return popular(memberId, false, pageable);
        }
        JavaType reasonsType = objectMapper.getTypeFactory().constructCollectionType(List.class, String.class);
        Page<StudyRecommendationResponse> result
            = rows
                .map(
                    row -> StudyRecommendationResponse
                        .from(row, objectMapper.readValue(row.getReasonsJson(), reasonsType))
                );
        return StudyRecommendationPageResponse.of(RecommendationMode.PERSONALIZED, false, result);
    }

    private StudyRecommendationPageResponse popular(Long memberId, boolean isProfileRequired, Pageable pageable) {
        LocalDateTime since = LocalDateTime.now(clock).minusDays(ACTIVITY_WINDOW_DAYS);
        Page<StudyRecommendationResponse> result
            = studyRecommendationQueryRepository
                .findPopularStudies(memberId, since, pageable)
                .map(StudyRecommendationResponse::from);
        return StudyRecommendationPageResponse.of(RecommendationMode.POPULAR, isProfileRequired, result);
    }

    // 하루 지난 추천이라도 오류보다 낫다. 재계산 트랜잭션만 롤백되어 기존 결과가 남고, 다음 조회나 매일 갱신에서 다시 계산한다.
    // 잠금 대기 초과·교착·DB 오류만 삼키고 프로그램 오류는 전파한다.
    private void refreshQuietly(Long memberId) {
        try {
            studyRecommendationRefreshService.refresh(memberId);
        } catch (DataAccessException | TransactionException e) {
            log.warn("추천 결과 재계산 실패, 기존 결과로 응답한다. memberId={}", memberId, e);
        }
    }
}
