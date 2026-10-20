package com.backtoback.notification.global.error;

import org.springframework.http.HttpStatus;

import com.backtoback.common.exception.ErrorCode;

import lombok.Getter;

/**
 * notification-service 전용 오류 코드 (API 명세서 0.4 Notification).
 * 공통 오류는 {@code CommonErrorCode}를 쓴다.
 */
@Getter
public enum NotificationErrorCode implements ErrorCode {

    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "알림이 없거나 본인 알림이 아닙니다.");

    private final HttpStatus status;
    private final String message;

    NotificationErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override
    public String getCode() {
        return name();
    }
}
