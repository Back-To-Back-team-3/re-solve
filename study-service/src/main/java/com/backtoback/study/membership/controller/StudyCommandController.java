package com.backtoback.study.membership.controller;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.web.logging.CorrelationIdFilter;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.study.membership.dto.StudyCreateRequest;
import com.backtoback.study.membership.dto.StudyCreateResponse;
import com.backtoback.study.membership.service.StudyCommandService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 스터디 생성·가입 명령 API (API 명세서 §5.2 외부 API — 스터디 생성·가입).
 */
@RestController
@RequestMapping("/api/v1/studies")
@RequiredArgsConstructor
public class StudyCommandController {

    private final StudyCommandService studyCommandService;

    /**
     * 5.2.1 스터디 생성 — 로그인한 회원이 스터디를 만들고 LEADER로 등록된다.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<StudyCreateResponse>> createStudy(
        @Valid @RequestBody StudyCreateRequest request,
        @AuthenticationPrincipal GatewayUser user
    ) {
        Long memberId = Long.parseLong(user.userId());
        // 필터는 최대 128자를 허용하지만 outbox_events.correlation_id는 VARCHAR(64) — 초과분은 잘라낸다
        String raw = MDC.get(CorrelationIdFilter.MDC_KEY);
        String correlationId = raw != null && raw.length() > 64 ? raw.substring(0, 64) : raw;
        Long studyId = studyCommandService.createStudy(memberId, request, correlationId);
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ApiResponse.success("스터디가 생성되었습니다.", new StudyCreateResponse(String.valueOf(studyId))));
    }
}
