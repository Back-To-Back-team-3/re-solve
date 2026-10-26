package com.backtoback.judge.global.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 서비스 공통 예외의 오류 계약과 내부 진단 정보 보존을 검증한다.
 */
class JudgeExceptionTest {
    /**
     * 하나의 예외가 모든 채점 오류 코드와 해당 기본 메시지를 유지하는지 검증한다.
     *
     * @param code 채점 오류 코드
     */
    @ParameterizedTest
    @EnumSource(JudgeErrorCode.class)
    @DisplayName("모든 채점 오류 코드와 기본 메시지를 하나의 서비스 예외로 전달한다")
    void preservesErrorCodeAndMessage(JudgeErrorCode code) {
        var exception = new JudgeException(code);

        assertThat(exception.getErrorCode()).isSameAs(code);
        assertThat(exception.getMessage()).isEqualTo(code.getMessage());
        assertThat(exception.getUpstreamStatus()).isNull();
    }

    /**
     * 외부 HTTP 상태를 보관해도 공개 오류 코드와 HTTP 상태를 덮어쓰지 않는지 검증한다.
     */
    @Test
    @DisplayName("외부 401 상태를 보관해도 공개 오류는 Judge0 응답 실패 503으로 유지한다")
    void preservesUpstreamStatusWithoutReplacingPublicError() {
        var exception = new JudgeException(JudgeErrorCode.JUDGE0_RESPONSE_FAILED, 401);

        assertThat(exception.getUpstreamStatus()).isEqualTo(401);
        assertThat(exception.getErrorCode()).isSameAs(JudgeErrorCode.JUDGE0_RESPONSE_FAILED);
        assertThat(exception.getErrorCode().getStatus().value()).isEqualTo(503);
    }
}
