package com.backtoback.notification.notification.controller;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.notification.notification.dto.response.NotificationPageResponse;
import com.backtoback.notification.notification.dto.response.UnreadCountResponse;
import com.backtoback.notification.notification.service.NotificationService;

import lombok.RequiredArgsConstructor;

/**
 * 알림 목록 조회 및 미읽음 개수 조회 REST API 컨트롤러 (API 명세서 7.1.2, 7.1.3).
 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /**
     * 7.1.2 알림 목록 조회 — 회원의 알림 목록을 페이징 조회하며 미읽음 개수를 함께 반환한다.
     *
     * @param memberId Gateway가 주입한 사용자 식별자
     * @param unreadOnly 미읽음 알림만 조회 여부 (기본값 false)
     * @param pageable 페이징 및 정렬 정보 (기본 createdAt DESC, size 20)
     * @return 공통 성공 응답 형식으로 감싼 알림 목록 페이징 결과
     */
    @GetMapping
    public ResponseEntity<ApiResponse<NotificationPageResponse>> getNotifications(
        @RequestHeader(name = "X-User-Id") Long memberId,
        @RequestParam(
            name = "unreadOnly",
            defaultValue = "false"
        ) boolean unreadOnly,
        @PageableDefault(
            size = 20,
            sort = "createdAt",
            direction = Sort.Direction.DESC
        ) Pageable pageable
    ) {
        NotificationPageResponse response = notificationService.getNotifications(memberId, unreadOnly, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 7.1.3 미읽음 개수 조회 — 회원의 미읽음 알림 총 개수를 반환한다.
     *
     * @param memberId Gateway가 주입한 사용자 식별자
     * @return 공통 성공 응답 형식으로 감싼 미읽음 개수
     */
    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<UnreadCountResponse>> getUnreadCount(
        @RequestHeader(name = "X-User-Id") Long memberId
    ) {
        UnreadCountResponse response = notificationService.getUnreadCount(memberId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
