package com.backtoback.problem.problem.dto.response;

import java.util.Map;

import com.backtoback.problem.problem.service.ProblemValidationDataStore.TestFile;

public record ValidationCaseResponse(
    String testAssetId,
    boolean isSample,
    Map<String, String> argumentTexts,
    String expectedReturnText
) {
    /**
     * 고정 테스트와 읽은 원문을 내부 응답으로 변환한다.
     *
     * @param test 고정 파일 참조
     * @param arguments 인자별 원문
     * @param expected 기대 반환값 원문
     * @return 검증 테스트 응답
     */
    public static ValidationCaseResponse from(TestFile test, Map<String, String> arguments, String expected) {
        return new ValidationCaseResponse(Long.toString(test.id()), test.sample(), arguments, expected);
    }
}
