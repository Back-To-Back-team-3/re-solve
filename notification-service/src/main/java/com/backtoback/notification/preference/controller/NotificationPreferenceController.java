package com.backtoback.notification.preference.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.notification.preference.dto.request.NotificationPreferenceUpdateRequest;
import com.backtoback.notification.preference.dto.response.NotificationPreferenceResponse;
import com.backtoback.notification.preference.service.NotificationPreferenceService;

import lombok.RequiredArgsConstructor;

/**
 * 알림 수신 설정 조회 및 변경 REST API 컨트롤러 (API 명세서 7.1.6).
 */
@RestController
@RequestMapping("/api/v1/notifications/preferences")
@RequiredArgsConstructor
public class NotificationPreferenceController {

    private final NotificationPreferenceService preferenceService;

    /**
     * 7.1.6 알림 수신 설정 조회 — 회원의 종류별 알림 수신 여부를 반환한다.
     * 행이 없으면 모두 {@code true}로 응답한다.
     *
     * @param memberId Gateway가 주입한 사용자 식별자
     * @return 공통 성공 응답 형식으로 감싼 알림 수신 설정
     */
    @GetMapping
    public ResponseEntity<ApiResponse<NotificationPreferenceResponse>> getPreferences(
        @RequestHeader(name = "X-User-Id") Long memberId
    ) {
        NotificationPreferenceResponse response = preferenceService.getPreferences(memberId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 7.1.6 알림 수신 설정 변경 — 회원의 종류별 알림 수신 여부를 부분 변경한다.
     * 최초 변경 시 기본값(모두 {@code true})으로 행을 생성한 뒤 요청에 포함된 필드만 갱신한다.
     *
     * @param memberId Gateway가 주입한 사용자 식별자
     * @param request 부분 변경할 알림 수신 설정 요청 바디
     * @return 공통 성공 응답 형식으로 감싼 변경 후 알림 수신 설정
     */
    @PatchMapping
    public ResponseEntity<ApiResponse<NotificationPreferenceResponse>> updatePreferences(
        @RequestHeader(name = "X-User-Id") Long memberId,
        @RequestBody NotificationPreferenceUpdateRequest request
    ) {
        NotificationPreferenceResponse response = preferenceService.updatePreferences(memberId, request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
