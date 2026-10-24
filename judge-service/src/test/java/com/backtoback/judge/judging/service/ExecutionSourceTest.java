package com.backtoback.judge.judging.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import com.backtoback.judge.judging.domain.JudgeLanguage;

/** 공통 실행 결과가 언어·원시 입력·고정 환경과 자원 제한을 요청에 보존하는지 검증한다. */
class ExecutionSourceTest {
    /**
     * 모든 언어에서 신규 실행과 고정 환경 실행을 같은 변환 경로로 연결한다.
     *
     * @param language 생성 결과의 언어
     */
    @ParameterizedTest
    @EnumSource(JudgeLanguage.class)
    @DisplayName("세 언어의 코드·입력·제한값과 선택적 고정 환경을 그대로 전달한다")
    void preservesExecutionRequest(JudgeLanguage language) {
        var source = new ExecutionSource(language, "execution-code", "encoded-input");
        var request = source.toRequest(null, 2000, 7000, 262144);
        assertThat(request.language()).isEqualTo(language.getCode());
        assertThat(request.sourceCode()).isEqualTo("execution-code");
        assertThat(request.stdin()).isEqualTo("encoded-input");
        assertThat(request.cpuTimeMs()).isEqualTo(2000);
        assertThat(request.wallTimeMs()).isEqualTo(7000);
        assertThat(request.memoryKiB()).isEqualTo(262144);
        assertThat(request.fixedRuntime()).isNull();

        var runtime = new JudgeRuntime(language.getCode(), 1, "fixed-runtime", "1", null);
        assertThat(source.toRequest(runtime, 2000, 7000, 262144).fixedRuntime()).isSameAs(runtime);
    }

    /**
     * 모든 언어 쌍에서 다른 언어의 고정 환경 연결을 거절한다.
     *
     * @param language 생성 코드의 언어
     * @param runtimeLanguage 고정 실행 환경의 언어
     */
    @ParameterizedTest
    @CsvSource(
        {
            "JAVA,PYTHON3",
            "JAVA,CPP",
            "PYTHON3,JAVA",
            "PYTHON3,CPP",
            "CPP,JAVA",
            "CPP,PYTHON3"
        }
    )
    @DisplayName("생성 코드와 다른 언어의 고정 실행 환경을 거절한다")
    void rejectsDifferentRuntime(JudgeLanguage language, JudgeLanguage runtimeLanguage) {
        var source = new ExecutionSource(language, "execution-code", "encoded-input");
        var runtime = new JudgeRuntime(runtimeLanguage.getCode(), 1, "other-runtime", "1", null);
        assertThatThrownBy(() -> source.toRequest(runtime, 2000, 7000, 262144))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** 실행 언어 없이 공통 결과를 생성할 수 없게 한다. */
    @Test
    @DisplayName("실행 언어가 누락된 결과를 거절한다")
    void rejectsMissingLanguage() {
        assertThatThrownBy(() -> new ExecutionSource(null, "execution-code", "encoded-input"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
