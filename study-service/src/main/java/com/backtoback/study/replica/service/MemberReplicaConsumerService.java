package com.backtoback.study.replica.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.event.store.JdbcProcessedEventStore;
import com.backtoback.study.replica.domain.MemberStatus;
import com.backtoback.study.replica.event.dto.MemberSuspendedPayload;
import com.backtoback.study.replica.event.dto.MemberUpdatedPayload;
import com.backtoback.study.replica.repository.MemberReplicaRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 회원 정보 변경·정지 이벤트를 소비해 회원 정보 복제본에 반영한다(event-contract.md §M1·§M2).
 * 탈퇴 반영과 스터디 가입 정리는 {@code MemberWithdrawn} 소비가 맡으며 여기서는 하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberReplicaConsumerService {

    private final Clock clock;
    private final JdbcProcessedEventStore processedEventStore;
    private final MemberReplicaRepository memberReplicaRepository;

    /**
     * 회원 정보 변경을 복제본에 반영한다. 같은 이벤트는 한 번만 처리하고, 순서 값이 같거나 낮은 이벤트와
     * 탈퇴 회원 이벤트는 저장소 조건부 쿼리가 무시한다.
     *
     * @throws RuntimeException 페이로드가 계약과 다를 때(검증 실패는 {@code IllegalArgumentException}, 헤더 형식 오류는
     *     페이로드의 {@code toMetadata()} 참고). 롤백 후 재수신을 거쳐 DLQ로 격리된다.
     */
    @Transactional
    public void consumeUpdated(MemberUpdatedPayload payload) {
        // 소비 기록을 첫 문장으로 남긴다. 이후 예외로 롤백되면 기록도 함께 롤백되어 재수신 때 다시 처리한다(event-contract.md §5.1).
        processedEventStore.insert(payload.toMetadata(), now());

        payload.validate();
        Long memberId = payload.memberIdAsLong();
        MemberStatus status = payload.memberStatus();

        // WITHDRAWN은 M1 계약상 유효 값이지만 반영하면 순서 값이 올라가 이후 MemberWithdrawn이 지연 이벤트로 판정되어
        // 가입 정리가 누락된다. 탈퇴 반영은 MemberWithdrawn 소비에 맡긴다(event-contract.md §M3).
        if (status == MemberStatus.WITHDRAWN) {
            log.warn("MemberUpdated 탈퇴 상태는 반영하지 않음 memberId={} sourceVersion={}", memberId, payload.sourceVersion());
            return;
        }

        memberReplicaRepository
            .upsertFromMemberUpdated(
                memberId,
                payload.nickname(),
                payload.profileImageUrl(),
                status.name(),
                payload.sourceVersion(),
                now()
            );
    }

    /**
     * 회원 정지·해제를 복제본 상태에 반영한다. 같은 이벤트는 한 번만 처리한다.
     * 복제본이 없는 회원은 닉네임이 없어 행을 만들지 않고 처리 기록만 남긴다. 이후 회원 정보 변경 이벤트의 상태로 반영된다.
     *
     * @throws RuntimeException 페이로드가 계약과 다를 때(검증 실패는 {@code IllegalArgumentException}, 헤더 형식 오류는
     *     페이로드의 {@code toMetadata()} 참고). 롤백 후 재수신을 거쳐 DLQ로 격리된다.
     */
    @Transactional
    public void consumeSuspended(MemberSuspendedPayload payload) {
        processedEventStore.insert(payload.toMetadata(), now());

        payload.validate();
        Long memberId = payload.memberIdAsLong();

        int updated
            = memberReplicaRepository
                .updateStatusFromMemberSuspended(
                    memberId,
                    payload.targetStatus().name(),
                    payload.sourceVersion(),
                    now()
                );
        if (updated > 0) {
            return;
        }

        if (memberReplicaRepository.findByMemberId(memberId).isEmpty()) {
            log.warn("MemberSuspended 복제본 없음, 반영 생략 memberId={} sourceVersion={}", memberId, payload.sourceVersion());
        } else {
            log
                .info(
                    "MemberSuspended 지연 또는 탈퇴 회원 이벤트 무시 memberId={} sourceVersion={}",
                    memberId,
                    payload.sourceVersion()
                );
        }
    }

    private LocalDateTime now() {
        // DATETIME(6)은 초과 자릿수를 반올림하므로 저장 전에 마이크로초로 자른다.
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
