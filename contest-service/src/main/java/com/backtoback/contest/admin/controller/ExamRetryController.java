package com.backtoback.contest.admin.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.contest.admin.dto.ContestOperationResponse;
import com.backtoback.contest.admin.dto.ExamRetryRequest;
import com.backtoback.contest.admin.dto.ExamRetryResponse;
import com.backtoback.contest.admin.service.ExamRetryService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 시험 실패 재처리와 관리자 작업 상태를 제공한다. */
@RestController
@RequiredArgsConstructor
public class ExamRetryController {
    private final ExamRetryService service;

    /** API 명세서 §4.6.3 실패 시험 제출 재처리. */
    @PostMapping("/api/v1/exams/{examId}/submissions/{examSubmissionId}/retry")
    public ResponseEntity<ApiResponse<ExamRetryResponse>> retry(
        @PathVariable String examId,
        @PathVariable String examSubmissionId,
        @AuthenticationPrincipal GatewayUser user,
        @Valid @RequestBody ExamRetryRequest request
    ) {
        return ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .body(ApiResponse.success("재처리를 요청했습니다.", service.request(examId, examSubmissionId, user, request)));
    }

    /** API 명세서 §4.6.7 관리자 작업 상태 조회. */
    @GetMapping("/api/v1/admin/contest-operations/{operationId}")
    public ResponseEntity<ApiResponse<ContestOperationResponse>> adminOperation(
        @PathVariable String operationId,
        @AuthenticationPrincipal GatewayUser user
    ) {
        return ResponseEntity.ok(ApiResponse.success(null, service.adminOperation(operationId, user)));
    }

    /** API 명세서 §4.6.7 시험 운영자의 작업 상태 조회. */
    @GetMapping("/api/v1/exams/{examId}/operations/{operationId}")
    public ResponseEntity<ApiResponse<ContestOperationResponse>> examOperation(
        @PathVariable String examId,
        @PathVariable String operationId,
        @AuthenticationPrincipal GatewayUser user
    ) {
        return ResponseEntity.ok(ApiResponse.success(null, service.examOperation(examId, operationId, user)));
    }
}
