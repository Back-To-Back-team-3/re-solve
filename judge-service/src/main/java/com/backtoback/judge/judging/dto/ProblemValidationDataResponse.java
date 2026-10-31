package com.backtoback.judge.judging.dto;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.springframework.util.Assert;

/**
 * 검증 실행 중에만 사용하는 Problem의 고정 원문이다. (API 명세서 §2.6.8)
 *
 * @param validationId 검증 ID
 * @param problemRevisionId 고정 회차 ID
 * @param language 검증 언어
 * @param requestHash 원 요청 해시
 * @param sourceCode 고정 코드 원문
 * @param functionSpec 함수 명세
 * @param executionLimits 최종 실행 제한
 * @param testCases 배열 순서가 실행 순서인 전체 테스트
 */
public record ProblemValidationDataResponse(
    String validationId,
    String problemRevisionId,
    String language,
    String requestHash,
    String sourceCode,
    FunctionSpec functionSpec,
    Limits executionLimits,
    List<TestCase> testCases
) {
    /**
     * 저장된 요청과 외부 자료의 연결 및 실행에 필요한 필수값을 검사한다.
     * 입력·반환값의 타입 검사는 기존 생성기와 판정기에 맡긴다.
     *
     * @param request 저장된 불변 검증 요청
     * @throws IllegalArgumentException 연결 정보·필수값·테스트 식별자가 올바르지 않은 경우
     */
    public void validate(ProblemValidationRequest request) {
        Assert.isTrue(Long.toString(request.validationId()).equals(validationId), "검증 ID가 일치하지 않습니다.");
        Assert.isTrue(Long.toString(request.problemRevisionId()).equals(problemRevisionId), "회차가 일치하지 않습니다.");
        Assert.isTrue(request.language().equals(language), "검증 언어가 일치하지 않습니다.");
        Assert.isTrue(request.requestHash().equals(requestHash), "요청 해시가 일치하지 않습니다.");
        Assert.hasText(sourceCode, "검증 코드가 필요합니다.");
        Assert.notNull(functionSpec, "함수 명세가 필요합니다.");
        Assert.notNull(executionLimits, "실행 제한이 필요합니다.");
        executionLimits.validate();
        Assert.notEmpty(testCases, "전체 테스트가 필요합니다.");
        var ids = new HashSet<Long>();
        for (var test : testCases) {
            Assert.notNull(test, "테스트가 필요합니다.");
            Assert
                .isTrue(
                    test.testAssetId() != null && test.testAssetId().matches("[1-9][0-9]{0,18}"),
                    "테스트 ID가 올바르지 않습니다."
                );
            Assert.isTrue(ids.add(Long.parseLong(test.testAssetId())), "테스트 ID가 중복됩니다.");
            Assert.notNull(test.isSample(), "테스트 공개 여부가 필요합니다.");
            Assert.notNull(test.argumentTexts(), "테스트 입력이 필요합니다.");
            Assert.notNull(test.expectedReturnText(), "테스트 기대값이 필요합니다.");
        }
    }

    /**
     * 최종 회차 실행 제한이다.
     *
     * @param timeMs CPU 시간 제한
     * @param wallTimeMs 경과 시간 제한
     * @param memoryKb 메모리 제한
     * @param outputLimitKb 반환값 제한
     */
    public record Limits(Integer timeMs, Integer wallTimeMs, Integer memoryKb, Integer outputLimitKb) {
        /**
         * 잘못된 외부 제한으로 실행하지 않도록 검사한다.
         *
         * @throws IllegalArgumentException 제한이 누락되거나 범위를 벗어난 경우
         */
        public void validate() {
            Assert.isTrue(timeMs != null && timeMs > 0, "CPU 시간 제한이 올바르지 않습니다.");
            Assert.isTrue(wallTimeMs != null && wallTimeMs >= timeMs, "경과 시간 제한이 올바르지 않습니다.");
            Assert.isTrue(memoryKb != null && memoryKb > 0, "메모리 제한이 올바르지 않습니다.");
            Assert.isTrue(outputLimitKb != null && outputLimitKb >= 1 && outputLimitKb <= 256, "반환값 제한이 올바르지 않습니다.");
        }
    }

    /**
     * 원문을 영속화하지 않는 테스트 자료다.
     *
     * @param testAssetId 고정 테스트 ID
     * @param isSample 공개 여부
     * @param argumentTexts 매개변수별 JSON 원문
     * @param expectedReturnText 기대값 JSON 원문
     */
    public record TestCase(
        String testAssetId,
        Boolean isSample,
        Map<String, String> argumentTexts,
        String expectedReturnText
    ) {
    }
}
