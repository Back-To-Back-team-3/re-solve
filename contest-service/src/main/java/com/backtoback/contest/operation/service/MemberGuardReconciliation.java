package com.backtoback.contest.operation.service;

import org.springframework.stereotype.Service;

import com.backtoback.contest.operation.client.MemberOperationClient;

import lombok.RequiredArgsConstructor;

/** 원격 확인을 잠금 밖에서 수행하고 현재 가드에만 반영한다. */
@Service
@RequiredArgsConstructor
public class MemberGuardReconciliation {
    private final MemberOperationClient client;
    private final MemberGuardTransitions transitions;

    /**
     * 잠금 없이 원래 회원 작업을 조회하고 현재 가드에만 종결 결과를 반영한다.
     * 조회 실패는 호출자에게 전달하며 {@code PENDING}은 준비 상태를 유지한다.
     *
     * @param memberId 회원 ID
     * @param operationId 원래 탈퇴 작업 ID
     * @param expectedVersion 조회 전에 관찰한 기대 원천 버전
     * @return 현재 가드에 완료·취소 결과를 반영했는지 여부
     */
    public boolean reconcile(long memberId, String operationId, long expectedVersion) {
        var result = client.find(memberId, operationId);
        return transitions.apply(memberId, operationId, expectedVersion, result);
    }
}
