package com.backtoback.study.assignment.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.study.assignment.dto.AssignedProblemInternalResponse;
import com.backtoback.study.assignment.service.AssignedProblemQueryService;

import lombok.RequiredArgsConstructor;

/**
 * judge가 호출하는 배정 문제 레벨 잠금 예외 확인 내부 API.
 * 내부 API 공통 규칙(API 명세서 0.3)에 따라 응답을 {@code ApiResponse}로 감싸지 않는다.
 */
@RestController
@RequestMapping("/internal/v1/studies/members")
@RequiredArgsConstructor
public class AssignedProblemInternalController {

    private final AssignedProblemQueryService assignedProblemQueryService;

    /**
     * 5.7.4 배정 문제 레벨 잠금 예외 확인 (내부) — 배정 문제가 아니거나 없는 문제도 200과 {@code assigned: false}를 반환한다.
     */
    @GetMapping("/{memberId}/assigned-problems/{problemId}")
    public AssignedProblemInternalResponse getAssignedProblem(
        @PathVariable Long memberId,
        @PathVariable Long problemId
    ) {
        boolean assigned = assignedProblemQueryService.isAssigned(memberId, problemId);
        return AssignedProblemInternalResponse.of(memberId, problemId, assigned);
    }
}
