package com.backtoback.study.membership.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.event.store.JdbcProcessedEventStore;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.study.membership.domain.MembershipStatus;
import com.backtoback.study.membership.domain.StudyMembershipHistory;
import com.backtoback.study.membership.repository.StudyMembershipHistoryRepository;
import com.backtoback.study.membership.repository.StudyMembershipRepository;
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

    @Transactional
    public void consume(MemberWithdrawnPayload payload) {
        try {
            processedEventStore.insert(payload.toMetadata(), LocalDateTime.now(clock));
        } catch (DuplicateKeyException e) {
            log.warn("MemberWithdrawn 중복 수신 무시 eventId={}", payload.eventId());
            return;
        }

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

        memberReplicaRepository.upsertWithdrawn(memberId, payload.sourceVersion(), leftAt);

        // TODO(#추천이슈): study_recommendations 삭제 — 추천 기능 구현 시 처리
        // TODO(#힌트이슈): hint_block_replicas 삭제 — 힌트 차단 구현 시 처리
    }
}
