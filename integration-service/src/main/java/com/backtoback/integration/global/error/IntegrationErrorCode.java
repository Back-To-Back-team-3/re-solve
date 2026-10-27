package com.backtoback.integration.global.error;

import org.springframework.http.HttpStatus;

import com.backtoback.common.exception.ErrorCode;

import lombok.Getter;

/**
 * integration-service 전용 오류 코드 (API 명세서 0.4 Notification · GitHub 및 8.1 GitHub).
 * 공통 오류는 {@code CommonErrorCode}를 쓴다.
 */
@Getter
public enum IntegrationErrorCode implements ErrorCode {

    GITHUB_NOT_CONNECTED(HttpStatus.NOT_FOUND, "GitHub 연동 정보가 존재하지 않습니다."),
    GITHUB_ALREADY_CONNECTED(HttpStatus.CONFLICT, "이미 GitHub 연동이 완료된 계정입니다."),
    GITHUB_INSTALLATION_INVALID(HttpStatus.BAD_REQUEST, "유효하지 않은 GitHub App 설치 정보입니다."),
    GITHUB_REPOSITORY_INVALID(HttpStatus.CONFLICT, "연동 가능한 GitHub 저장소 조건에 맞지 않습니다."),
    GITHUB_SYNC_NOT_ALLOWED(HttpStatus.CONFLICT, "GitHub 자동 커밋 동기화가 불가능한 상태입니다."),
    GITHUB_SYNC_JOB_NOT_FOUND(HttpStatus.NOT_FOUND, "GitHub 동기화 작업이 존재하지 않습니다."),
    GITHUB_AUTH_EXPIRED(HttpStatus.UNAUTHORIZED, "GitHub 연동 권한이 만료되었습니다.");

    private final HttpStatus status;
    private final String message;

    IntegrationErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override
    public String getCode() {
        return name();
    }
}
