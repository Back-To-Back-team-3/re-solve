package com.backtoback.study.global.error;

import org.springframework.http.HttpStatus;

import com.backtoback.common.exception.ErrorCode;

import lombok.Getter;

/**
 * study-service 전용 오류 코드 (에러 코드 명세서 § study). 공통 오류는 {@code CommonErrorCode}를 쓴다.
 * 가입·운영·문제집 등 이후 기능의 오류 코드도 이 enum에 그룹으로 추가한다.
 */
@Getter
public enum StudyErrorCode implements ErrorCode {

    // 탐색 (discovery)
    STUDY_NOT_FOUND(HttpStatus.NOT_FOUND, "스터디를 찾을 수 없습니다."),

    // 생성·가입 (membership)
    STUDY_JOIN_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "참여할 수 있는 스터디 수(최대 5개)를 초과했습니다."),
    MEMBER_SUSPENDED(HttpStatus.FORBIDDEN, "정지된 회원은 스터디 활동이 제한됩니다."),
    STUDY_NOT_RECRUITING(HttpStatus.CONFLICT, "모집 중인 스터디가 아닙니다."),
    STUDY_FULL(HttpStatus.CONFLICT, "스터디 정원이 초과되었습니다."),
    STUDY_ALREADY_APPLIED(HttpStatus.CONFLICT, "이미 가입 신청 또는 가입된 스터디입니다."),
    STUDY_MEMBERSHIP_NOT_FOUND(HttpStatus.NOT_FOUND, "가입 신청 또는 구성원을 찾을 수 없습니다."),
    STUDY_LEADER_DELEGATION_REQUIRED(HttpStatus.CONFLICT, "스터디장은 위임 후에만 탈퇴할 수 있습니다."),
    STUDY_MEMBERSHIP_STATUS_CONFLICT(HttpStatus.CONFLICT, "이미 처리된 가입 신청입니다."),
    STUDY_APPLICATION_EXPIRED(HttpStatus.CONFLICT, "만료된 가입 신청입니다."),
    STUDY_ALREADY_CLOSED(HttpStatus.CONFLICT, "종료된 스터디입니다."),

    // 문제집 (assignment)
    STUDY_ASSIGNMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "문제집을 찾을 수 없습니다."),
    STUDY_ASSIGNMENT_PROBLEM_NOT_AVAILABLE(HttpStatus.CONFLICT, "문제집에 넣을 문제가 GENERAL·PUBLIC이 아님");

    private final HttpStatus status;
    private final String message;

    StudyErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override
    public String getCode() {
        return name();
    }
}
