package com.backtoback.study.recommendation.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.study.recommendation.domain.StudyRecommendation;
import com.backtoback.study.recommendation.repository.RecommendationCandidateRow;
import com.backtoback.study.recommendation.repository.StudyRecommendationQueryRepository;
import com.backtoback.study.recommendation.repository.StudyRecommendationRepository;
import com.backtoback.study.replica.domain.MemberLearningProfileReplica;
import com.backtoback.study.replica.domain.MemberStatus;
import com.backtoback.study.replica.repository.MemberLearningProfileReplicaRepository;
import com.backtoback.study.replica.repository.MemberReplicaRepository;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/**
 * 회원 한 명의 규칙 기반 추천 결과를 다시 계산해 저장한다. 학습 프로필 변경 소비·추천 갱신 스케줄러·조회 시 재계산이
 * 모두 이 진입점을 쓴다(스케줄러 명세 5.4). 추천 조회 응답은 만들지 않는다.
 */
@Service
@RequiredArgsConstructor
public class StudyRecommendationRefreshService {

    static final int MAX_RECOMMENDATIONS = 50;

    private static final int SCORE_SCALE = 2;

    private static final Comparator<ScoredCandidate> RECOMMENDATION_ORDER
        = Comparator
            .comparingInt(ScoredCandidate::score)
            .reversed()
            .thenComparing(ScoredCandidate::lastActivityAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(ScoredCandidate::studyId, Comparator.reverseOrder());

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final MemberReplicaRepository memberReplicaRepository;
    private final MemberLearningProfileReplicaRepository learningProfileReplicaRepository;
    private final StudyRecommendationQueryRepository queryRepository;
    private final StudyRecommendationRepository recommendationRepository;
    private final StudyRecommendationScorer scorer;

    /**
     * 회원의 기존 추천 결과를 한 트랜잭션에서 새 결과로 교체한다(ERD 4.5.11). 0점을 뺀 상위 50건을 점수 → 최근 활동 → 최신
     * 스터디 순으로 저장한다(스케줄러 명세 5.4). 학습 프로필 입력이 없거나 탈퇴한 회원은 기존 결과만 삭제한다.
     * 같은 회원의 재계산이 동시에 실행되면 학습 프로필 복제본 행 잠금으로 직렬화되어 나중에 커밋한 결과만 남는다.
     */
    @Transactional
    public void refresh(Long memberId) {
        // 탈퇴 소비·학습 프로필 소비와 같은 순서(회원 행 → 학습 프로필 행)로 잠가 교착을 피한다.
        boolean withdrawn
            = memberReplicaRepository
                .findByMemberIdWithForShare(memberId)
                .map(replica -> replica.getMemberStatus() == MemberStatus.WITHDRAWN)
                .orElse(false);
        Optional<MemberLearningProfileReplica> profileReplica
            = learningProfileReplicaRepository.findByMemberIdForUpdate(memberId);

        recommendationRepository.deleteAllByMemberId(memberId);
        if (withdrawn || profileReplica.isEmpty() || !profileReplica.get().hasProfileInput()) {
            return;
        }

        RecommendationProfile profile = RecommendationProfile.from(profileReplica.get(), objectMapper);
        LocalDateTime generatedAt = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        List<StudyRecommendation> recommendations
            = queryRepository
                .findRecommendationCandidates(memberId)
                .stream()
                .map(candidate -> new ScoredCandidate(candidate, scorer.score(profile, candidate)))
                // 근거 없는 추천을 만들지 않도록 0점은 저장하지 않는다(정책 및 상태 8.2 > 추천 설명).
                .filter(scored -> scored.score() > 0)
                .sorted(RECOMMENDATION_ORDER)
                .limit(MAX_RECOMMENDATIONS)
                .map(scored -> toRecommendation(memberId, scored, generatedAt))
                .toList();
        recommendationRepository.saveAll(recommendations);
    }

    private StudyRecommendation toRecommendation(Long memberId, ScoredCandidate scored, LocalDateTime generatedAt) {
        return StudyRecommendation
            .create(
                memberId,
                scored.studyId(),
                BigDecimal.valueOf(scored.score()).setScale(SCORE_SCALE),
                objectMapper.writeValueAsString(scored.result().reasons()),
                generatedAt
            );
    }

    private record ScoredCandidate(RecommendationCandidateRow candidate, RecommendationScore result) {

        int score() {
            return result.score();
        }

        LocalDateTime lastActivityAt() {
            return candidate.getLastActivityAt();
        }

        Long studyId() {
            return candidate.getStudyId();
        }
    }
}
