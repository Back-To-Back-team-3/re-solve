package com.backtoback.judge.global.error;

import org.springframework.http.HttpStatus;

import com.backtoback.common.exception.ErrorCode;

import lombok.Getter;

@Getter
public enum JudgeErrorCode implements ErrorCode {
    JUDGE0_CONNECTION_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "채점 서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    JUDGE0_RESPONSE_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "채점 서버의 응답을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    JUDGE0_CONFIGURATION_INVALID(HttpStatus.SERVICE_UNAVAILABLE, "채점 서버 설정이 준비되지 않았습니다. 잠시 후 다시 시도해 주세요."),
    SUBMISSION_RUNTIME_UNAVAILABLE(HttpStatus.CONFLICT, "기존 실행 환경을 사용할 수 없어 재채점할 수 없습니다."),
    UNSUPPORTED_LANGUAGE(HttpStatus.BAD_REQUEST, "지원하지 않거나 사용할 수 없는 언어입니다.");

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
