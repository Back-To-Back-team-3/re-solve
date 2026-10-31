package com.backtoback.judge.submission.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.backtoback.judge.global.error.JudgeException;

/** 요청 해시와 UTF-8·식별자·키 경계를 검증한다. */
class SubmissionCreateRequestTest {
    /** 같은 원본은 같은 해시이며 코드의 공백·개행은 보존한다. */
    @Test
    @DisplayName("같은 원본은 같은 해시이고 코드 공백과 개행이 다르면 다른 요청이다")
    void preservesOriginalCode() {
        var request = new SubmissionCreateRequest("301", "1001", "java", "return 1;");
        request.validate("submit-001");
        assertThat(request.requestHash()).hasSize(64).isEqualTo(request.requestHash());
        assertThat(new SubmissionCreateRequest("301", "1001", "java", "return 1;\n").requestHash())
            .isNotEqualTo(request.requestHash());
        assertThat(new SubmissionCreateRequest("301", "1002", "java", "return 1;").requestHash())
            .isNotEqualTo(request.requestHash());
    }

    /** UTF-8 다중 바이트 코드의 허용 경계와 초과를 구분한다. */
    @Test
    @DisplayName("UTF-8 64KiB 경계는 허용하고 다중 바이트 초과는 413이다")
    void checksUtf8Size() {
        new SubmissionCreateRequest("1", "2", "java", "가".repeat(21845) + "a").validate("key");
        assertThatThrownBy(() -> new SubmissionCreateRequest("1", "2", "java", "가".repeat(21846)).validate("key"))
            .isInstanceOfSatisfying(
                JudgeException.class,
                exception -> assertThat(exception.getErrorCode().getCode()).isEqualTo("CODE_TOO_LARGE")
            );
    }

    /**
     * 잘못된 식별자와 키를 거절한다.
     *
     * @param id 범위를 벗어나거나 정규 형식이 아닌 ID
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "0",
            "-1",
            "01",
            "9223372036854775808",
            "1.0",
            ""
        }
    )
    @DisplayName("양수 64비트 정규 식별자가 아니면 공통 요청 오류다")
    void rejectsId(String id) {
        assertThatThrownBy(() -> new SubmissionCreateRequest(id, "2", "java", "code").validate("key"))
            .isInstanceOf(JudgeException.class);
    }

    /** ASCII 키 길이와 필수 조건을 검증한다. */
    @Test
    @DisplayName("1부터 100자 ASCII 키는 허용하고 비ASCII 또는 초과 키는 거절한다")
    void checksKey() {
        var request = new SubmissionCreateRequest("1", "2", "java", "code");
        request.validate("x".repeat(100));
        assertThatThrownBy(() -> request.validate("한글")).isInstanceOf(JudgeException.class);
        assertThatThrownBy(() -> request.validate("x".repeat(101))).isInstanceOf(JudgeException.class);
        assertThatThrownBy(() -> request.validate(" ")).isInstanceOf(JudgeException.class);
    }
}
