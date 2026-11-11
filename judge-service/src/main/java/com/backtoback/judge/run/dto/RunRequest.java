package com.backtoback.judge.run.dto;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.judge.global.error.JudgeErrorCode;
import com.backtoback.judge.global.error.JudgeException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 고정 회차에 대해 공개 예제 또는 직접 입력을 실행하는 요청이다. (API 명세서 §3.1.8) */
public record RunRequest(
    @NotBlank String problemId,
    @NotBlank String problemRevisionId,
    @NotBlank String language,
    @NotBlank String sourceCode,
    Boolean useSamples,
    @Size(max = 5) List<@NotNull @Valid CustomInput> customInputs
) {
    /** 실행 대상과 UTF-8 코드 크기를 확인한다. */
    public void validate() {
        if (language == null || language.isBlank() || sourceCode == null || sourceCode.isBlank()
            || !Boolean.TRUE.equals(useSamples) && (customInputs == null || customInputs.isEmpty())
            || customInputs != null && (customInputs.size() > 5
                || customInputs.stream().anyMatch(value -> value == null || value.argumentTexts() == null))) {
            throw new JudgeException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        if (sourceCode.getBytes(StandardCharsets.UTF_8).length > 64 * 1024) {
            throw new JudgeException(JudgeErrorCode.CODE_TOO_LARGE);
        }
    }

    /** 매개변수 이름별 JSON 원문을 보존하는 직접 입력이다. */
    public record CustomInput(@NotNull Map<@NotBlank String, @NotNull String> argumentTexts) {
    }
}
