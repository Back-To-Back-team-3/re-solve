package com.backtoback.judge.submission.dto;

import java.util.List;
import java.util.Map;

import com.backtoback.judge.judging.domain.JudgeTestResult;
import com.backtoback.judge.judging.domain.JudgeVerdict;
import com.backtoback.judge.judging.dto.SubmissionJudgingDataResponse.TestContent;
import com.fasterxml.jackson.annotation.JsonInclude;

/** 숨김 테스트의 응답 타입 자체를 분리해 원문·판정·측정값이 직렬화되지 않도록 한다. */
public record SubmissionTestResultsResponse(List<TestResult> content) {
    public static SubmissionTestResultsResponse from(List<TestResult> content) {
        return new SubmissionTestResultsResponse(List.copyOf(content));
    }

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
        public static PublicTest from(JudgeTestResult test, JudgeVerdict verdict, TestContent source) {
            return new PublicTest(
                test.getTestOrder(),
                true,
                verdict,
                verdict == null ? null : test.getTimeMs(),
                verdict == null ? null : test.getMemoryKb(),
                source.argumentTexts(),
                source.expectedReturnText(),
                verdict == null ? null : test.getActualOutput()
            );
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record HiddenTest(int testOrder, Boolean passed) implements TestResult {
        public static HiddenTest from(int testOrder, JudgeVerdict verdict) {
            return new HiddenTest(testOrder, verdict == null ? null : verdict == JudgeVerdict.AC);
        }
    }
}
