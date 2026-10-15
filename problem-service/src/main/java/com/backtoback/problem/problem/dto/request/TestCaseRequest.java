package com.backtoback.problem.problem.dto.request;

import java.util.Map;

/**
 * 테스트 한 건의 공개 여부와 정밀도를 보존한 입력·기대값 원문을 받는다.
 *
 * @param isSample 공개 예제 여부; 공개 예제와 숨김 테스트 모두 채점에 사용
 * @param argumentTexts 매개변수 이름별 JSON 텍스트
 * @param expectedReturnText 기대 반환값 하나의 JSON 텍스트
 */
public record TestCaseRequest(Boolean isSample, Map<String, String> argumentTexts, String expectedReturnText) {
}
