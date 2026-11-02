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
    MEMBER_SUSPENDED(HttpStatus.FORBIDDEN, "정지된 회원은 스터디를 생성할 수 없습니다.");

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
