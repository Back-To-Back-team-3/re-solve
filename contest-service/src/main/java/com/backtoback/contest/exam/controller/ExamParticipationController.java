package com.backtoback.contest.exam.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.contest.exam.dto.ExamCancellationResponse;
import com.backtoback.contest.exam.dto.ExamEntryResponse;
import com.backtoback.contest.exam.dto.ExamParticipationResponse;
import com.backtoback.contest.exam.service.ExamParticipationService;

import lombok.RequiredArgsConstructor;

/** 시험 참가 등록과 입장, 취소를 제공한다. */
@RestController
@RequiredArgsConstructor
public class ExamParticipationController {
    private final ExamParticipationService service;

    /** API 명세서 4.2.1 시험 참가 등록 — 기존 활성 참가를 재사용한다. */
    @PostMapping("/api/v1/exams/{examId}/participants")
    public ResponseEntity<ApiResponse<ExamParticipationResponse>> register(
        @PathVariable String examId,
        @AuthenticationPrincipal GatewayUser user
    ) {
        var result = service.register(examId, user.userId());
        return ResponseEntity
            .status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
            .body(ApiResponse.success("참가 등록이 완료되었습니다.", result.response()));
    }

    /** API 명세서 4.2.2 시험 입장 — 최초 입장 시각을 유지한다. */
    @PostMapping("/api/v1/exams/{examId}/start")
    public ResponseEntity<ApiResponse<ExamEntryResponse>> enter(
        @PathVariable String examId,
        @AuthenticationPrincipal GatewayUser user
    ) {
        return ResponseEntity.ok(ApiResponse.success(null, service.enter(examId, user.userId())));
    }

    /** API 명세서 4.2.10 시험 참가 취소 — 기존 취소의 응답을 유지한다. */
    @PostMapping("/api/v1/exams/{examId}/participants/me/cancellation")
    public ResponseEntity<ApiResponse<ExamCancellationResponse>> cancel(
        @PathVariable String examId,
        @AuthenticationPrincipal GatewayUser user
    ) {
        return ResponseEntity.ok(ApiResponse.success("참가가 취소되었습니다.", service.cancel(examId, user.userId())));
    }
}
