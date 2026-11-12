package com.backtoback.problem.admin.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.problem.admin.dto.OutboxReprocessRequest;
import com.backtoback.problem.admin.dto.OutboxReprocessResponse;
import com.backtoback.problem.admin.service.AdminOutboxEventService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

/** 관리자의 실패 Outbox 이벤트 재처리 API를 제공한다. */
@RestController
@RequiredArgsConstructor
public class AdminOutboxEventController {

    private final AdminOutboxEventService service;

    /**
     * {@code FAILED} 상태의 Outbox 이벤트를 {@code PENDING}으로 되돌려 재발행을 접수한다. API 명세서 §2.8.1을 따른다.
     *
     * @param outboxEventId 재처리할 Outbox 이벤트 ID
     * @param user Gateway 인증 관리자
     * @param body 재처리 사유
     * @param request 감사 주소를 읽을 HTTP 요청
     * @return 재처리 접수 202 응답
     * @throws com.backtoback.problem.global.exception.ProblemException 대상 없음·기간 초과·상태 충돌인 경우
     */
    @PostMapping("/api/v1/admin/problems/outbox-events/{outboxEventId}/reprocesses")
    public ResponseEntity<ApiResponse<OutboxReprocessResponse>> reprocess(
        @PathVariable @Positive(message = "Outbox 이벤트 ID는 양수여야 합니다.") long outboxEventId,
        @AuthenticationPrincipal GatewayUser user,
        @Valid @RequestBody OutboxReprocessRequest body,
        HttpServletRequest request
    ) {
        var id
            = service.reprocess(outboxEventId, Long.parseLong(user.userId()), body.reason(), request.getRemoteAddr());
        return ResponseEntity.accepted().body(ApiResponse.success("재처리를 접수했습니다.", OutboxReprocessResponse.from(id)));
    }
}
