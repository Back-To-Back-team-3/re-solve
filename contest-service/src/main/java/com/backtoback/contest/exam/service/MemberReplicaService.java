package com.backtoback.contest.exam.service;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.client.MemberClient;
import com.backtoback.contest.exam.client.MemberStatusResponse;
import com.backtoback.contest.exam.repository.MemberReplicaRepository;

import lombok.RequiredArgsConstructor;

/**
 * 회원 조회 결과를 복제본과 동기화한다 (ERD 4.4.17).
 */
@Service
@RequiredArgsConstructor
public class MemberReplicaService {
    private final MemberClient memberClient;
    private final MemberReplicaRepository members;
    private final Clock clock;
    private final TransactionTemplate transactions;

    /**
     * HTTP 호출을 저장 트랜잭션 밖에서 수행한 뒤 저장된 최신 상태를 반환한다.
     */
    public String refresh(long memberId) {
        var response = memberClient.fetch(memberId);
        return transactions.execute(status -> saveSnapshot(memberId, response));
    }

    /**
     * 버전 비교 저장과 최신 상태 조회를 한 트랜잭션에 수행한다.
     */
    private String saveSnapshot(long memberId, MemberStatusResponse response) {
        response.validate(memberId);
        members
            .sync(
                memberId,
                response.nickname(),
                response.profileImageUrl(),
                response.status(),
                response.sourceVersion(),
                LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE)
            );
        return members.findStatus(memberId).orElseThrow();
    }
}
