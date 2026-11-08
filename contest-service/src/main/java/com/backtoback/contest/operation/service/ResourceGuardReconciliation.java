package com.backtoback.contest.operation.service;

import org.springframework.stereotype.Service;

import com.backtoback.contest.operation.client.ResourceOperationClient;

import lombok.RequiredArgsConstructor;

/** 스터디·문제의 원래 작업을 잠금 밖에서 확인한다. */
@Service
@RequiredArgsConstructor
public class ResourceGuardReconciliation {
    private final ResourceOperationClient client;
    private final ContestGuardWorkflowService workflow;

    /**
     * 소유 서비스 조회는 트랜잭션 밖에서 수행하고 현재 작업과 기대 버전을 잠금 아래 다시 확인한다.
     *
     * @param type 스터디 또는 문제 자원 종류
     * @param resource 소유 서비스 자원 ID
     * @param operationId 조회할 원래 작업 ID
     * @param expected 조회 전에 관찰한 기대 원천 버전
     * @return 현재 작업에 결과를 반영했는지 여부
     */
    public boolean reconcile(String type, long resource, String operationId, long expected) {
        var result = client.find(type, resource, operationId);
        return workflow.reconcile(type, resource, operationId, expected, result);
    }
}
