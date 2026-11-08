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

    /** 현재 작업과 기대 버전이 바뀌면 늦은 조회 결과를 적용하지 않는다. */
    public boolean reconcile(String type, long resource, String operationId, long expected) {
        var result = client.find(type, resource, operationId);
        return workflow.reconcile(type, resource, operationId, expected, result);
    }
}
