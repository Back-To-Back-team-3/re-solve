package com.backtoback.judge.global.error;

import org.springframework.http.HttpStatus;

import com.backtoback.common.exception.ErrorCode;

import lombok.Getter;

/**
 * 채점 서비스의 오류 코드·HTTP 상태·사용자 안내를 정의한다. (에러 코드 표 > Submission · Run)
 */
@Getter
public enum JudgeErrorCode implements ErrorCode {
    JUDGE0_CONNECTION_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "채점 서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    JUDGE0_RESPONSE_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "채점 서버의 응답을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    JUDGE0_CONFIGURATION_INVALID(HttpStatus.SERVICE_UNAVAILABLE, "채점 서버 설정이 준비되지 않았습니다. 잠시 후 다시 시도해 주세요."),
    SUBMISSION_RUNTIME_UNAVAILABLE(HttpStatus.CONFLICT, "지정된 실행 환경을 사용할 수 없습니다."),
    UNSUPPORTED_LANGUAGE(HttpStatus.BAD_REQUEST, "지원하지 않거나 사용할 수 없는 언어입니다."),
    CODE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "제출 코드는 UTF-8 64KiB 이하여야 합니다."),
    SUBMISSION_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "제출 요청 한도를 초과했습니다."),
    // 일반·시험이 공유하는 사용자별 동시 실행 제한을 넘은 요청이다.
    RUN_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "이미 코드를 실행하고 있습니다."),
    // 동기 실행의 전체 기한을 넘긴 요청이다.
    RUN_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "코드 실행 제한 시간을 초과했습니다."),
    SUBMISSION_NOT_FOUND(HttpStatus.NOT_FOUND, "제출을 찾을 수 없습니다."),
    PROBLEM_VALIDATION_NOT_FOUND(HttpStatus.NOT_FOUND, "검증 작업을 찾을 수 없습니다."),
    PROBLEM_NOT_AVAILABLE(HttpStatus.NOT_FOUND, "제출할 수 없는 문제입니다."),
    LEVEL_PROBLEM_LOCKED(HttpStatus.FORBIDDEN, "현재 태그 레벨에서 제출할 수 없는 문제입니다."),
    MEMBER_SUSPENDED(HttpStatus.FORBIDDEN, "정지된 회원은 제출할 수 없습니다.");

    private final HttpStatus status;
    private final String message;

    /**
     * 오류 코드의 HTTP 상태와 사용자 안내 메시지를 설정한다.
     *
     * @param status 응답 HTTP 상태
     * @param message 사용자에게 전달할 오류 메시지
     */
    JudgeErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    /** {@inheritDoc} */
    @Override
    public String getCode() {
        return name();
    }
}
