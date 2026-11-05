package com.backtoback.contest.exam.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.contest.exam.dto.ExamCreateRequest;
import com.backtoback.contest.exam.dto.ExamResponse;
import com.backtoback.contest.exam.service.ExamManagementService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 스터디 시험의 생성과 설정 변경을 제공한다. */
@RestController
@RequiredArgsConstructor
public class ExamManagementController {
    private final ExamManagementService service;

    /** API 명세서 §4.1.1 시험 생성 — 선택한 문제의 현재 사용 회차를 고정한다. */
    @PostMapping("/api/v1/studies/{studyId}/exams")
    public ResponseEntity<ApiResponse<ExamResponse>> create(
        @PathVariable String studyId,
        @AuthenticationPrincipal GatewayUser user,
        @Valid @RequestBody ExamCreateRequest request
    ) {
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ApiResponse.success("시험이 생성되었습니다.", service.create(studyId, user.userId(), request)));
    }
}
