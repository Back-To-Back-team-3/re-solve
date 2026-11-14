package com.backtoback.problem.admin.controller;

import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.web.logging.CorrelationIdFilter;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.problem.problem.dto.request.ProblemValidationRequest;
import com.backtoback.problem.problem.dto.response.ProblemValidationResponse;
import com.backtoback.problem.problem.service.ProblemCreateBodyReader;
import com.backtoback.problem.problem.service.ProblemValidationService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 작성 관리자의 고정 회차 검증 코드를 접수한다. */
@RestController
@RequiredArgsConstructor
public class AdminProblemValidationController {
    private final ProblemValidationService service;
    private final ProblemCreateBodyReader identities;

    /**
     * 검증 코드를 비동기 요청으로 접수한다. API 명세서 §2.3.10을 따른다.
     *
     * @param problemId 소속 문제
     * @param problemRevisionId 고정 회차
     * @param user 인증 관리자
     * @param key 멱등키
     * @param body 언어와 코드 원문
     * @param request 감사 주소를 읽을 요청
     * @return 최초 접수 202 응답
     */
    @PostMapping("/api/v1/admin/problems/{problemId}/revisions/{problemRevisionId}/validations")
    public ResponseEntity<ApiResponse<ProblemValidationResponse>> accept(
        @PathVariable long problemId,
        @PathVariable long problemRevisionId,
        @AuthenticationPrincipal GatewayUser user,
        @RequestHeader("Idempotency-Key") String key,
        @Valid @RequestBody ProblemValidationRequest body,
        HttpServletRequest request
    ) {
        var response
            = service
                .accept(
                    problemId,
                    problemRevisionId,
                    identities.authorId(user.userId()),
                    key,
                    body,
                    MDC.get(CorrelationIdFilter.MDC_KEY),
                    request.getRemoteAddr()
                );
        return ResponseEntity.accepted().body(ApiResponse.success("검증 코드가 접수되었습니다.", response));
    }
}
