package com.backtoback.study.membership.controller;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.web.logging.CorrelationIdFilter;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.study.membership.domain.MembershipStatus;
import com.backtoback.study.membership.dto.MembershipDecisionRequest;
import com.backtoback.study.membership.dto.MembershipDecisionResponse;
import com.backtoback.study.membership.dto.StudyJoinResponse;
import com.backtoback.study.membership.service.StudyMembershipCommandService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 스터디 가입·탈퇴 명령 API (API 명세서 §5.2.2·§5.2.3).
 */
@RestController
@RequestMapping("/api/v1/studies/{studyId}/memberships")
@RequiredArgsConstructor
public class StudyMembershipCommandController {

    private final StudyMembershipCommandService studyMembershipCommandService;

    /**
     * 5.2.2 스터디 가입 신청·즉시 가입.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<StudyJoinResponse>> join(
        @PathVariable Long studyId,
        @AuthenticationPrincipal GatewayUser user
    ) {
        Long memberId = Long.parseLong(user.userId());
        String raw = MDC.get(CorrelationIdFilter.MDC_KEY);
        String correlationId = raw != null && raw.length() > 64 ? raw.substring(0, 64) : raw;
        StudyJoinResponse response = studyMembershipCommandService.join(studyId, memberId, correlationId);
        String message
            = MembershipStatus.PENDING.name().equals(response.status()) ? "가입 신청이 완료되었습니다." : "스터디에 가입되었습니다.";
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(message, response));
    }

    /**
     * 5.2.3 가입 신청 취소·탈퇴.
     */
    @DeleteMapping("/me")
    public ResponseEntity<Void> cancelOrLeave(@PathVariable Long studyId, @AuthenticationPrincipal GatewayUser user) {
        Long memberId = Long.parseLong(user.userId());
        String raw = MDC.get(CorrelationIdFilter.MDC_KEY);
        String correlationId = raw != null && raw.length() > 64 ? raw.substring(0, 64) : raw;
        studyMembershipCommandService.cancelOrLeave(studyId, memberId, correlationId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 5.2.4 가입 신청 승인·거절·강제탈퇴.
     */
    @PatchMapping("/{membershipId}")
    public ResponseEntity<ApiResponse<MembershipDecisionResponse>> decide(
        @PathVariable Long studyId,
        @PathVariable Long membershipId,
        @Valid @RequestBody MembershipDecisionRequest request,
        @AuthenticationPrincipal GatewayUser user
    ) {
        Long requestMemberId = Long.parseLong(user.userId());
        String raw = MDC.get(CorrelationIdFilter.MDC_KEY);
        String correlationId = raw != null && raw.length() > 64 ? raw.substring(0, 64) : raw;

        String message;
        if (request.status() == MembershipStatus.APPROVED) {
            studyMembershipCommandService.approve(studyId, membershipId, requestMemberId, correlationId);
            message = "승인 처리가 완료되었습니다.";
        } else if (request.status() == MembershipStatus.REJECTED) {
            studyMembershipCommandService.reject(studyId, membershipId, requestMemberId, correlationId);
            message = "거절 처리가 완료되었습니다.";
        } else {
            studyMembershipCommandService
                .remove(studyId, membershipId, requestMemberId, request.reason(), correlationId);
            message = "강제 탈퇴 처리가 완료되었습니다.";
        }
        return ResponseEntity
            .ok(
                ApiResponse
                    .success(
                        message,
                        new MembershipDecisionResponse(String.valueOf(membershipId), request.status().name())
                    )
            );
    }
}
