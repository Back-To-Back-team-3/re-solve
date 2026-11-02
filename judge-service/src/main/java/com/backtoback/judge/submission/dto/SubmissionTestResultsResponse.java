package com.backtoback.judge.submission.dto;

import java.util.List;
import java.util.Map;

import com.backtoback.judge.judging.domain.JudgeTestResult;
import com.backtoback.judge.judging.domain.JudgeVerdict;
import com.backtoback.judge.judging.dto.SubmissionJudgingDataResponse.TestContent;
import com.fasterxml.jackson.annotation.JsonInclude;

/** 숨김 테스트의 응답 타입 자체를 분리해 원문·판정·측정값이 직렬화되지 않도록 한다. */
public record SubmissionTestResultsResponse(List<TestResult> content) {
    /**
     * 공개 범위가 적용된 결과 목록을 복사하여 응답으로 구성한다.
     *
     * @param content 고정 테스트 순서의 공개·숨김 결과
     * @return 변경 불가능한 결과 목록 응답
     */
    public static SubmissionTestResultsResponse from(List<TestResult> content) {
        return new SubmissionTestResultsResponse(List.copyOf(content));
    }

    /** 공개·숨김 결과가 공유하는 테스트 순서 계약. */
    public sealed interface TestResult permits PublicTest, HiddenTest {
        /**
         * 고정 회차에서 부여한 테스트 순서를 반환한다.
         *
         * @return 테스트 순서
         */
        int testOrder();
    }

    /** 공개 테스트 원문과 현재 실행 시도의 확정 결과. */
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
        /**
         * 공개 원문과 결과를 조합하며 미확정 시도의 측정값·출력은 숨긴다.
         *
         * @param test 저장된 공개 테스트 결과
         * @param verdict 현재 시도의 공개 가능한 판정, 미확정이면 null
         * @param source 버전·체크섬 검증을 마친 공개 원문
         * @return 공개 테스트 결과 응답
         */
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

    /** 숨김 테스트의 번호와 통과 여부만 포함하는 응답. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record HiddenTest(int testOrder, Boolean passed) implements TestResult {
        /**
         * 숨김 테스트 판정을 통과 여부로만 변환한다.
         *
         * @param testOrder 고정 테스트 순서
         * @param verdict 현재 시도의 공개 가능한 판정, 미확정이면 null
         * @return 번호와 통과 여부, 미확정이면 통과 여부는 null
         */
        public static HiddenTest from(int testOrder, JudgeVerdict verdict) {
            return new HiddenTest(testOrder, verdict == null ? null : verdict == JudgeVerdict.AC);
        }
    }
}
