package com.backtoback.study.membership.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.event.store.JdbcProcessedEventStore;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.study.hint.repository.HintBlockReplicaRepository;
import com.backtoback.study.membership.domain.MembershipStatus;
import com.backtoback.study.membership.domain.StudyMembershipHistory;
import com.backtoback.study.membership.repository.StudyMembershipHistoryRepository;
import com.backtoback.study.membership.repository.StudyMembershipRepository;
import com.backtoback.study.recommendation.repository.StudyRecommendationRepository;
import com.backtoback.study.replica.domain.MemberReplica;
import com.backtoback.study.replica.event.dto.MemberWithdrawnPayload;
import com.backtoback.study.replica.repository.MemberLearningProfileReplicaRepository;
import com.backtoback.study.replica.repository.MemberReplicaRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * MemberWithdrawn 이벤트를 소비하여 탈퇴 회원의 스터디 가입·신청을 정리한다
 * (event-contract.md §M3, §4.4 소비자 처리표).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberWithdrawnConsumerService {

    private final Clock clock;
    private final JdbcProcessedEventStore processedEventStore;
    private final StudyMembershipCommandService membershipCommandService;
    private final StudyMembershipRepository membershipRepository;
    private final StudyMembershipHistoryRepository historyRepository;
    private final MemberReplicaRepository memberReplicaRepository;
    private final MemberLearningProfileReplicaRepository learningProfileReplicaRepository;
    private final HintBlockReplicaRepository hintBlockReplicaRepository;
    private final StudyRecommendationRepository studyRecommendationRepository;

    @Transactional
    public void consume(MemberWithdrawnPayload payload) {
        processedEventStore.insert(payload.toMetadata(), LocalDateTime.now(clock));

        Long memberId = Long.parseLong(payload.memberId());

        Optional<MemberReplica> replicaOpt = memberReplicaRepository.findByMemberId(memberId);
        if (replicaOpt.isPresent() && payload.sourceVersion() <= replicaOpt.get().getSourceVersion()) {
            log.warn("MemberWithdrawn 지연 이벤트 무시 memberId={} sourceVersion={}", memberId, payload.sourceVersion());
            return;
        }

        LocalDateTime leftAt
            = OffsetDateTime
                .parse(payload.withdrawnAt())
                .toInstant()
                .atZone(ClockConfiguration.SERVICE_ZONE)
                .toLocalDateTime()
                .truncatedTo(ChronoUnit.MICROS);

        // 회원 복제본 행을 먼저 탈퇴로 바꿔 잠근다. 학습 프로필 소비가 같은 행을 잠금 조회하므로, 프로필 복제본 삭제 뒤에
        // 프로필 소비가 행을 다시 만들지 못한다. 두 경로 모두 회원 행 → 프로필 행 순서로 잠가 교착을 피한다.
        memberReplicaRepository.upsertWithdrawn(memberId, payload.sourceVersion(), leftAt);

        membershipRepository
            .findAllByMemberIdAndStatus(memberId, MembershipStatus.APPROVED)
            .forEach(
                m -> membershipCommandService.applyLeaveInternal(m, "MEMBER_WITHDRAWN", payload.eventId(), leftAt)
            );

        membershipRepository.findAllByMemberIdAndStatus(memberId, MembershipStatus.PENDING).forEach(m -> {
            membershipRepository.cancelIfPending(m.getId());
            historyRepository
                .save(
                    StudyMembershipHistory
                        .record(
                            m.getId(),
                            MembershipStatus.PENDING,
                            MembershipStatus.CANCELED,
                            m.getRole(),
                            m.getRole(),
                            memberId,
                            null,
                            leftAt
                        )
                );
        });

        learningProfileReplicaRepository.deleteByMemberId(memberId);
        hintBlockReplicaRepository.deleteByMemberId(memberId);
        // 추천 결과는 파생 데이터라 물리 삭제한다(정책 및 상태 §9.4 회원 탈퇴 전파, ERD 4.5.11). 재계산과 같은 순서
        // (회원 행 → 학습 프로필 행 → 추천 행)로 잠가 교착을 피한다.
        studyRecommendationRepository.deleteAllByMemberId(memberId);
    }
}
