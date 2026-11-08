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

    /** API 명세서 §4.7.3 자원 작업 준비. */
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

    /** API 명세서 §4.7.4 자원 작업 완료. */
    @PostMapping("/internal/v1/contest/guards/{resourceType}/{resourceId}/complete")
    public ResponseEntity<GuardCompletionResponse> complete(
        @PathVariable String resourceType,
        @PathVariable String resourceId,
        @Valid @RequestBody GuardFinishRequest request
    ) {
        return ResponseEntity.ok(service.finish(resourceType, resourceId, request, true));
    }

    /** API 명세서 §4.7.4 자원 작업 취소. */
    @PostMapping("/internal/v1/contest/guards/{resourceType}/{resourceId}/cancel")
    public ResponseEntity<GuardCompletionResponse> cancel(
        @PathVariable String resourceType,
        @PathVariable String resourceId,
        @Valid @RequestBody GuardFinishRequest request
    ) {
        return ResponseEntity.ok(service.finish(resourceType, resourceId, request, false));
    }
}
