package com.backtoback.member.skill.dto;

import java.util.List;

import com.backtoback.member.skill.client.JudgeDiagnosisRunClient;
import com.backtoback.member.skill.service.DiagnosisRunService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/** 진단의 현재 코드를 제출과 별개로 실행하는 요청이다(API 명세서 §1.5.9). */
public record DiagnosisRunRequest(
    @NotBlank(message = "언어는 필수입니다.") String language,
    @NotBlank(message = "코드를 입력해 주세요.") String sourceCode,
    @Size(
        max = 10,
        message = "직접 입력은 최대 10개입니다."
    ) List<@NotNull(message = "직접 입력 항목은 비워 둘 수 없습니다.") @Valid CustomTest> customTests
) {
    /**
     * 외부 요청을 실행 명령으로 변환한다.
     *
     * @return 직접 입력 생략을 빈 목록으로 정규화한 명령
     */
    public DiagnosisRunService.Command toCommand() {
        var tests
            = customTests == null ? List.<JudgeDiagnosisRunClient.CustomTest>of()
                : customTests.stream().map(test -> new JudgeDiagnosisRunClient.CustomTest(test.args())).toList();
        return new DiagnosisRunService.Command(language, sourceCode, tests);
    }

    /** 고정 함수 명세의 순서를 따르는 직접 입력이다. */
    public record CustomTest(@NotNull(message = "인수 배열은 필수입니다.") List<JsonNode> args) {
    }
}
