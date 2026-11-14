package com.backtoback.member.global.error;

import org.springframework.http.HttpStatus;

import com.backtoback.common.exception.ErrorCode;

import lombok.Getter;

/**
 * member-service 전용 오류 코드 (API 명세서 0.4 Auth·Member). 공통 오류는 {@code CommonErrorCode}를 쓴다.
 */
@Getter
public enum MemberErrorCode implements ErrorCode {

    // Auth
    AUTH_REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "로그인이 만료되었습니다. 다시 로그인해 주세요."),
    AUTH_REFRESH_TOKEN_REUSED(HttpStatus.UNAUTHORIZED, "보안을 위해 모든 기기에서 로그아웃되었습니다. 다시 로그인해 주세요."),
    AUTH_LOGIN_CODE_INVALID(HttpStatus.UNAUTHORIZED, "로그인 코드가 없거나 만료되었습니다. 다시 로그인해 주세요."),
    AUTH_OAUTH_DENIED(HttpStatus.UNAUTHORIZED, "GitHub 로그인이 취소되었습니다."),
    AUTH_OAUTH_FAILED(HttpStatus.BAD_GATEWAY, "GitHub 로그인에 실패했습니다. 잠시 후 다시 시도해 주세요."),

    // Member
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    MEMBER_SUSPENDED(HttpStatus.FORBIDDEN, "정지된 회원은 이용할 수 없습니다."),
    MEMBER_NICKNAME_INVALID(HttpStatus.BAD_REQUEST, "닉네임은 이모지 없이 2~50자로 입력해 주세요."),
    WITHDRAWAL_BLOCKED_ACTIVE_EXAM(HttpStatus.CONFLICT, "입장한 시험·대회의 결과가 확정된 뒤에 탈퇴할 수 있습니다."),
    WITHDRAWAL_BLOCKED_STUDY_LEADER(HttpStatus.CONFLICT, "스터디장을 다른 구성원에게 위임하거나 스터디를 종료한 뒤에 탈퇴할 수 있습니다."),

    // Level
    LEVEL_TAG_NOT_FOUND(HttpStatus.NOT_FOUND, "태그를 찾을 수 없습니다."),
    LEVEL_ALREADY_ENTERED(HttpStatus.CONFLICT, "이미 시작한 태그입니다."),

    // Diagnosis
    DIAGNOSIS_NOT_FOUND(HttpStatus.NOT_FOUND, "진단을 찾을 수 없습니다."),
    DIAGNOSIS_NOT_ELIGIBLE(HttpStatus.CONFLICT, "Lv.3 이상은 진단을 볼 수 없습니다."),
    DIAGNOSIS_COOLDOWN(HttpStatus.CONFLICT, "직전 진단이 끝나고 3일이 지나야 다시 볼 수 있습니다."),
    DIAGNOSIS_ALREADY_IN_PROGRESS(HttpStatus.CONFLICT, "이 태그의 진단이 이미 진행 중입니다."),
    DIAGNOSIS_PROBLEMS_UNAVAILABLE(HttpStatus.CONFLICT, "이 태그는 진단 문제가 부족합니다. Lv.1로 시작해 주세요."),
    DIAGNOSIS_CLOSED(HttpStatus.CONFLICT, "종료된 진단입니다."),
    CODE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "코드는 64KB 이하로 작성해 주세요."),
    UNSUPPORTED_LANGUAGE(HttpStatus.BAD_REQUEST, "지원하지 않는 언어입니다."),
    SUBMISSION_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "같은 문항은 5초 뒤에 다시 제출할 수 있습니다.");

    private final HttpStatus status;
    private final String message;

    MemberErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override
    public String getCode() {
        return name();
    }
}
