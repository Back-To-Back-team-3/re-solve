package com.backtoback.contest.exam.service;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.client.MemberClient;
import com.backtoback.contest.exam.client.MemberStatusResponse;
import com.backtoback.contest.exam.repository.MemberReplicaRepository;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

import lombok.RequiredArgsConstructor;

/**
 * 회원 상태를 확인하고 버전이 있는 표시 정보만 복제본에 동기화한다 (ERD 4.4.17).
 */
@Service
@RequiredArgsConstructor
public class MemberReplicaService {
    private final MemberClient memberClient;
    private final MemberReplicaRepository members;
    private final Clock clock;
    private final TransactionTemplate transactions;

    /**
     * HTTP 호출을 트랜잭션 밖에서 수행하고 버전이 있는 스냅샷만 저장한다.
     */
    public String refresh(long memberId) {
        var response = memberClient.fetch(memberId);
        response.validate(memberId);
        return transactions.execute(status -> {
            if (response.hasSnapshotFields()) {
                saveSnapshot(memberId, response);
                return members.findStatus(memberId).orElseThrow();
            }
            if (!"ACTIVE".equals(response.status())) {
                return response.status();
            }
            return members.findStatus(memberId).orElse(response.status());
        });
    }

    /** 현재 회원 상태가 정지 또는 탈퇴라면 신규 시험 작업을 차단한다. */
    public static void requireActiveStatus(String status) {
        if ("SUSPENDED".equals(status)) {
            throw new ContestException(ContestErrorCode.MEMBER_SUSPENDED);
        }
        if (!"ACTIVE".equals(status)) {
            throw new ContestException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }
    }

    /**
     * 시험 저장 트랜잭션에서 이미 반영된 회원 상태를 잠그고 확인한다.
     *
     * @throws ContestException 활성 복제본이 없으면 {@code AUTH_ACCESS_DENIED}
     */
    public void requireActiveForExamWrite(long memberId) {
        if (members.lockStatus(memberId).filter("ACTIVE"::equals).isEmpty()) {
            throw new ContestException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }
    }

    /**
     * 원천 버전을 비교해 표시 정보를 저장한다.
     */
    private void saveSnapshot(long memberId, MemberStatusResponse response) {
        response.validateSnapshot(memberId);
        members
            .sync(
                memberId,
                response.nickname(),
                response.profileImageUrl(),
                response.status(),
                response.sourceVersion(),
                LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE)
            );
    }
}
