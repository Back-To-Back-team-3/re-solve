package com.backtoback.study.operation.controller;

import java.util.Optional;

import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.web.logging.CorrelationIdFilter;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.study.operation.dto.ForceCloseRequest;
import com.backtoback.study.operation.dto.StudyCloseResponse;
import com.backtoback.study.operation.service.StudyOperationService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 스터디 관리자 API (API 명세서 §5.6 — ADMIN 전용).
 */
@RestController
@RequestMapping("/api/v1/admin/studies")
@RequiredArgsConstructor
public class StudyAdminController {

    private final StudyOperationService studyOperationService;

    /**
     * §5.6.1 관리자 강제 스터디 종료 — {@code ADMIN}만 호출할 수 있다.
     */
    @PostMapping("/{studyId}/close")
    public ResponseEntity<ApiResponse<StudyCloseResponse>> forceCloseStudy(
        @PathVariable Long studyId,
        @AuthenticationPrincipal GatewayUser user,
        @Valid @RequestBody ForceCloseRequest request,
        HttpServletRequest httpRequest
    ) {
        Long actorId = Long.parseLong(user.userId());
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        String clientIp = resolveClientIp(httpRequest);
        return ResponseEntity
            .ok(
                ApiResponse
                    .success(
                        "스터디가 강제 종료되었습니다.",
                        studyOperationService.forceCloseStudy(studyId, actorId, request, clientIp, correlationId)
                    )
            );
    }

    private String resolveClientIp(HttpServletRequest req) {
        return Optional
            .ofNullable(req.getHeader("X-Forwarded-For"))
            .map(h -> h.split(",")[0].trim())
            .orElse(req.getRemoteAddr());
    }
}
