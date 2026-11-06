package com.backtoback.contest.exam.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.api.PageResponse;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.contest.exam.dto.ExamListResponse;
import com.backtoback.contest.exam.service.ExamQueryService;

import lombok.RequiredArgsConstructor;

/** 인증된 사용자의 시험 조회를 제공한다. */
@RestController
@RequiredArgsConstructor
public class ExamQueryController {
    private final ExamQueryService service;

    /** API 명세서 4.1.2 스터디 시험 목록을 조회한다. */
    @GetMapping("/api/v1/studies/{studyId}/exams")
    public ResponseEntity<ApiResponse<PageResponse<ExamListResponse>>> list(
        @PathVariable String studyId,
        @AuthenticationPrincipal GatewayUser user,
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "startsAt,desc") String sort
    ) {
        return ResponseEntity
            .ok(ApiResponse.success(null, service.list(studyId, user.userId(), status, page, size, sort)));
    }
}
