package com.backtoback.problem.global.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;

@Getter
public enum ErrorCode {
    // 공통
    COMMON_INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청을 확인해 주세요."),
    COMMON_INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),
    AUTH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "인증 정보가 없거나 유효하지 않습니다."),
    AUTH_ACCESS_DENIED(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.UNPROCESSABLE_CONTENT, "같은 멱등키로 다른 요청을 보낼 수 없습니다."),
    // PROBLEM
    PROBLEM_TEST_CASE_INVALID(HttpStatus.BAD_REQUEST, "테스트 데이터의 타입·구성 또는 중복 여부를 확인해 주세요."),
    PROBLEM_TAG_NOT_FOUND(HttpStatus.NOT_FOUND, "태그를 찾을 수 없습니다."),
    PROBLEM_TAG_DUPLICATED(HttpStatus.CONFLICT, "같은 이름의 태그가 이미 존재합니다."),
    PROBLEM_TAG_IN_USE(HttpStatus.CONFLICT, "문제 회차에서 사용 중인 태그입니다."),
    PROBLEM_VERSION_CONFLICT(HttpStatus.CONFLICT, "다른 요청으로 변경되었습니다. 다시 조회해 주세요.");

    private final HttpStatus status;
    private final String message;

    /**
     * 오류 코드의 HTTP 상태와 사용자 안내 메시지를 설정한다.
     *
     * @param status 응답 HTTP 상태
     * @param message 사용자에게 전달할 오류 메시지
     */
    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

}
