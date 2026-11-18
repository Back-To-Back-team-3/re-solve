package com.backtoback.study.assignment.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.study.assignment.dto.AssignedProblemResponse;
import com.backtoback.study.assignment.service.AssignedProblemQueryService;

import lombok.RequiredArgsConstructor;

/**
 * 문제 화면이 호출하는 배정 문제 레벨 잠금 예외 확인 API.
 * 판정은 judge가 쓰는 내부 API와 같은 서비스를 공유한다.
 */
@RestController
@RequestMapping("/api/v1/studies/me/assigned-problems")
@RequiredArgsConstructor
public class AssignedProblemController {

    private final AssignedProblemQueryService assignedProblemQueryService;

    /**
     * 5.4.5 배정 문제 레벨 잠금 예외 확인 — 배정 문제가 아니거나 없는 문제도 200과 {@code assigned: false}를 반환한다.
     */
    @GetMapping("/{problemId}")
    public ResponseEntity<ApiResponse<AssignedProblemResponse>> getAssignedProblem(
        @PathVariable Long problemId,
        @AuthenticationPrincipal GatewayUser user
    ) {
        Long memberId = Long.parseLong(user.userId());
        boolean assigned = assignedProblemQueryService.isAssigned(memberId, problemId);
        return ResponseEntity.ok(ApiResponse.success(AssignedProblemResponse.from(problemId, assigned)));
    }
}
