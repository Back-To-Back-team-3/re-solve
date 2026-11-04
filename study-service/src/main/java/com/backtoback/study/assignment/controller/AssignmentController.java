package com.backtoback.study.assignment.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.study.assignment.dto.AssignmentCreateRequest;
import com.backtoback.study.assignment.dto.AssignmentCreateResponse;
import com.backtoback.study.assignment.service.AssignmentCommandService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 문제집 명령 API (API 명세서 §5.4).
 */
@RestController
@RequestMapping("/api/v1/studies/{studyId}/assignments")
@RequiredArgsConstructor
public class AssignmentController {

    private final AssignmentCommandService assignmentCommandService;

    /**
     * 5.4.1 문제집 생성 — LEADER·MANAGER만 호출할 수 있다.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<AssignmentCreateResponse>> createAssignment(
        @PathVariable Long studyId,
        @Valid @RequestBody AssignmentCreateRequest request,
        @AuthenticationPrincipal GatewayUser user
    ) {
        Long memberId = Long.parseLong(user.userId());
        AssignmentCreateResponse response = assignmentCommandService.createAssignment(studyId, memberId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("문제집이 생성되었습니다.", response));
    }
}
