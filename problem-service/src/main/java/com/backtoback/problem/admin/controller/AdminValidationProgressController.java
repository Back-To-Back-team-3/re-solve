package com.backtoback.problem.admin.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.problem.problem.dto.response.AdminValidationProgressResponse;
import com.backtoback.problem.problem.service.ProblemCreateBodyReader;
import com.backtoback.problem.problem.service.ValidationProgressService;

import lombok.RequiredArgsConstructor;

/** 관리자 검증 진행 조회 API. */
@RestController
@RequiredArgsConstructor
public class AdminValidationProgressController {
    private final ValidationProgressService service;
    private final ProblemCreateBodyReader identities;

    /**
     * 문제 작성 관리자에게 검증 상태와 결과 반영 여부를 제공한다(API §2.3.11).
     *
     * @param validationId 검증 ID
     * @param user 인증 관리자
     * @return 현재 검증 진행 정보
     */
    @GetMapping("/api/v1/admin/problems/validations/{validationId}")
    public ResponseEntity<ApiResponse<AdminValidationProgressResponse>> read(
        @PathVariable long validationId,
        @AuthenticationPrincipal GatewayUser user
    ) {
        return ResponseEntity.ok(ApiResponse.success(service.read(validationId, identities.authorId(user.userId()))));
    }
}
