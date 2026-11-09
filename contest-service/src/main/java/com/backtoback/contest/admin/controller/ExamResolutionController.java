package com.backtoback.contest.admin.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.contest.admin.dto.ExamRefinalizationRequest;
import com.backtoback.contest.admin.dto.ExamRefinalizationResponse;
import com.backtoback.contest.admin.dto.ExamResolutionRequest;
import com.backtoback.contest.admin.dto.ExamResolutionResponse;
import com.backtoback.contest.admin.service.ExamResolutionService;
import com.backtoback.contest.global.error.ContestException;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 관리자가 실패 시험 접수를 종결하고 확정 결과를 재집계하는 API다. */
@RestController
@RequiredArgsConstructor
public class ExamResolutionController {
    private final ExamResolutionService service;

    /**
     * API 명세서 §4.6.4 실패 제출 종결 — 시험 실패 접수를 사유와 함께 0점으로 확정한다.
     *
     * @param context 시험 문맥인 {@code EXAM}
     * @param receiptId 시험 접수 ID
     * @param user Gateway가 인증한 관리자
     * @param request 종결 작업과 기대 채점 회차
     * @return 종결된 접수와 결과 버전
     * @throws ContestException 권한·대상·회차 또는 작업 ID가 일치하지 않는 경우
     */
    @PostMapping("/api/v1/admin/contest-submissions/{context}/{receiptId}/resolution")
    public ResponseEntity<ApiResponse<ExamResolutionResponse>> resolve(
        @PathVariable String context,
        @PathVariable String receiptId,
        @AuthenticationPrincipal GatewayUser user,
        @Valid @RequestBody ExamResolutionRequest request
    ) {
        return ResponseEntity
            .ok(ApiResponse.success("종결 처리가 완료되었습니다.", service.resolve(context, receiptId, user, request)));
    }

    /**
     * API 명세서 §4.6.5 시험 결과 재확정 — 기존 유효 제출로 새 불변 결과를 저장한다.
     *
     * @param examId 시험 ID
     * @param user Gateway가 인증한 관리자
     * @param request 작업 ID와 이전 결과 버전
     * @return 해당 작업으로 저장한 확정 버전과 시각
     * @throws ContestException 권한·상태·버전 또는 작업 ID가 일치하지 않는 경우
     */
    @PostMapping("/api/v1/admin/exams/{examId}/results/refinalization")
    public ResponseEntity<ApiResponse<ExamRefinalizationResponse>> refinalize(
        @PathVariable String examId,
        @AuthenticationPrincipal GatewayUser user,
        @Valid @RequestBody ExamRefinalizationRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success("결과를 재확정했습니다.", service.refinalize(examId, user, request)));
    }
}
