package com.backtoback.judge.submission.dto;

import java.util.List;
import java.util.Map;

import com.backtoback.judge.judging.domain.JudgeVerdict;
import com.fasterxml.jackson.annotation.JsonInclude;

/** 숨김 테스트의 응답 타입 자체를 분리해 원문·판정·측정값이 직렬화되지 않도록 한다. */
public record SubmissionTestResultsResponse(List<TestResult> content) {
    public sealed interface TestResult permits PublicTest, HiddenTest {
        int testOrder();
    }

    public record PublicTest(
        int testOrder,
        boolean isSample,
        JudgeVerdict verdict,
        Integer timeMs,
        Integer memoryKb,
        Map<String, String> argumentTexts,
        String expectedReturnText,
        String actualReturnText
    ) implements TestResult {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record HiddenTest(int testOrder, Boolean passed) implements TestResult {
    }
}
