package com.backtoback.problem.admin.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.problem.problem.dto.request.ProblemCreateRequest;
import com.backtoback.problem.problem.dto.response.ProblemCreateResponse;
import com.backtoback.problem.problem.service.ProblemCreateBodyReader;
import com.backtoback.problem.problem.service.ProblemCreateService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 작성 관리자의 비공개 문제 최초 등록을 제공한다. */
@RestController
@RequiredArgsConstructor
public class AdminProblemController {
    private final ProblemCreateService service;
    private final ProblemCreateBodyReader reader;

    /**
     * 전체 입력으로 비공개 첫 회차를 등록한다. (API 명세서 §2.3.1)
     *
     * @param user Gateway가 인증한 관리자
     * @param key 최초 등록 멱등키
     * @param body 검증된 전체 등록 입력
     * @param request 감사용 클라이언트 주소를 읽을 HTTP 요청
     * @return 최초 식별자·버전과 201 응답
     */
    @PostMapping(
        value = "/api/v1/admin/problems",
        consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ApiResponse<ProblemCreateResponse>> create(
        @AuthenticationPrincipal GatewayUser user,
        @RequestHeader("Idempotency-Key") String key,
        @Valid @RequestBody ProblemCreateRequest body,
        HttpServletRequest request
    ) {
        var response = service.create(reader.authorId(user.userId()), key, body, request.getRemoteAddr());
        return ResponseEntity.status(201).body(ApiResponse.success("문제가 등록되었습니다.", response));
    }
}
