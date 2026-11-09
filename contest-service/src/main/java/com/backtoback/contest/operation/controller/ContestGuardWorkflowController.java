package com.backtoback.contest.operation.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.contest.exam.dto.GuardCompletionResponse;
import com.backtoback.contest.exam.dto.GuardFinishRequest;
import com.backtoback.contest.exam.dto.GuardPreparationResponse;
import com.backtoback.contest.exam.dto.GuardPrepareRequest;
import com.backtoback.contest.global.error.ContestException;
import com.backtoback.contest.operation.service.ContestGuardWorkflowService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 소유 서비스가 자원 작업을 준비하고 종결하는 내부 API다. */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "contest.internal-http.mode",
    havingValue = "real"
)
public class ContestGuardWorkflowController {
    private final ContestGuardWorkflowService service;

    /**
     * API 명세서 §4.7.3 자원 작업 준비 — 신규 편입을 차단하고 기존 시험 사용을 확인한다.
     * 진행 시험의 종료를 기다리는 스터디 준비는 202를 반환한다.
     *
     * @param resourceType 소유 서비스 자원 종류
     * @param resourceId 소유 서비스 자원 ID
     * @param request 작업 ID·유형과 기대 원천 버전
     * @return 준비 상태와 최초 취소·현재 대기 시험 목록
     * @throws ContestException 사용 중 자원, 점유된 작업 또는 요청 충돌인 경우
     */
    @PostMapping("/internal/v1/contest/guards/{resourceType}/{resourceId}/prepare")
    public ResponseEntity<GuardPreparationResponse> prepare(
        @PathVariable String resourceType,
        @PathVariable String resourceId,
        @Valid @RequestBody GuardPrepareRequest request
    ) {
        var result = service.prepare(resourceType, resourceId, request);
        boolean waiting = "STUDY".equals(resourceType) && "PREPARED".equals(result.state()) && !result.ready();
        return ResponseEntity.status(waiting ? 202 : 200).body(result);
    }

    /**
     * API 명세서 §4.7.4 자원 작업 완료 — 현재 준비 작업과 소유 서비스의 결과 버전을 확인한다.
     *
     * @param resourceType 소유 서비스 자원 종류
     * @param resourceId 소유 서비스 자원 ID
     * @param request 원래 작업 ID와 완료 버전
     * @return 완료 후 가드 상태와 최신 원천 버전
     * @throws ContestException 작업·버전 불일치 또는 종료 대기 중인 경우
     */
    @PostMapping("/internal/v1/contest/guards/{resourceType}/{resourceId}/complete")
    public ResponseEntity<GuardCompletionResponse> complete(
        @PathVariable String resourceType,
        @PathVariable String resourceId,
        @Valid @RequestBody GuardFinishRequest request
    ) {
        return ResponseEntity.ok(service.finish(resourceType, resourceId, request, true));
    }

    /**
     * API 명세서 §4.7.4 자원 작업 취소 — 미실행이 확정된 작업의 준비 전 상태를 복원한다.
     *
     * @param resourceType 소유 서비스 자원 종류
     * @param resourceId 소유 서비스 자원 ID
     * @param request 원래 작업 ID와 기대 버전
     * @return 취소 후 가드 상태와 최신 원천 버전
     * @throws ContestException 작업·버전 불일치 또는 이미 완료된 경우
     */
    @PostMapping("/internal/v1/contest/guards/{resourceType}/{resourceId}/cancel")
    public ResponseEntity<GuardCompletionResponse> cancel(
        @PathVariable String resourceType,
        @PathVariable String resourceId,
        @Valid @RequestBody GuardFinishRequest request
    ) {
        return ResponseEntity.ok(service.finish(resourceType, resourceId, request, false));
    }
}
