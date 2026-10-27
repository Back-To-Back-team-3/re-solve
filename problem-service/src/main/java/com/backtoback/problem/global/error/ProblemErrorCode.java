package com.backtoback.problem.global.error;

import org.springframework.http.HttpStatus;

import com.backtoback.common.exception.ErrorCode;

import lombok.Getter;

/**
 * 태그와 문제 회차 변경 오류의 상태와 안내 메시지를 정의한다. (에러 코드 표 Problem · Solution)
 */
@Getter
public enum ProblemErrorCode implements ErrorCode {
    PROBLEM_TEST_CASE_INVALID(HttpStatus.BAD_REQUEST, "테스트 데이터의 타입·구성 또는 중복 여부를 확인해 주세요."),
    PROBLEM_TAG_NOT_FOUND(HttpStatus.NOT_FOUND, "태그를 찾을 수 없습니다."),
    PROBLEM_TAG_DUPLICATED(HttpStatus.CONFLICT, "같은 이름의 태그가 이미 존재합니다."),
    PROBLEM_TAG_IN_USE(HttpStatus.CONFLICT, "문제 회차에서 사용 중인 태그입니다."),
    PROBLEM_VERSION_CONFLICT(HttpStatus.CONFLICT, "다른 요청으로 변경되었습니다. 다시 조회해 주세요.");

    private final HttpStatus status;
    private final String message;

    ProblemErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override
    public String getCode() {
        return name();
    }
}
