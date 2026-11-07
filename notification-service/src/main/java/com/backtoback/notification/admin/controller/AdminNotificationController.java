package com.backtoback.notification.admin.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.notification.admin.dto.request.AdminNotificationBroadcastRequest;
import com.backtoback.notification.admin.dto.response.AdminNotificationBroadcastResponse;
import com.backtoback.notification.admin.service.AdminNotificationService;
import com.backtoback.notification.global.error.NotificationException;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 7.2 관리자 알림 API 컨트롤러.
 */
@RestController
@RequestMapping("/api/v1/admin/notifications")
@RequiredArgsConstructor
public class AdminNotificationController {

    private static final String ADMIN_ROLE = "ADMIN";
    private final AdminNotificationService adminNotificationService;

    /**
     * 7.2.1 시스템 전역 공지 알림 브로드캐스트 발송.
     *
     * @param adminMemberId 관리자 회원 ID (Gateway X-User-Id)
     * @param role 사용자 권한 (Gateway X-User-Role)
     * @param request 공지 발송 요청 DTO
     * @return 발송 성공 응답
     */
    @PostMapping("/broadcast")
    public ResponseEntity<ApiResponse<AdminNotificationBroadcastResponse>> broadcast(
        @RequestHeader(name = "X-User-Id") Long adminMemberId,
        @RequestHeader(
            name = "X-User-Role",
            required = false
        ) String role,
        @Valid @RequestBody AdminNotificationBroadcastRequest request
    ) {
        if (!ADMIN_ROLE.equals(role)) {
            throw new NotificationException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }

        AdminNotificationBroadcastResponse response
            = adminNotificationService.broadcastSystemNotice(adminMemberId, request);

        return ResponseEntity.ok(ApiResponse.success("시스템 공지 알림이 성공적으로 발송되었습니다.", response));
    }
}
