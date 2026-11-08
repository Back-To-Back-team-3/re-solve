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

    /** 조회 불명이나 PENDING은 기존 PREPARED 상태를 유지한다. */
    public boolean reconcile(long memberId, String operationId, long expectedVersion) {
        var result = client.find(memberId, operationId);
        return transitions.apply(memberId, operationId, expectedVersion, result);
    }
}
