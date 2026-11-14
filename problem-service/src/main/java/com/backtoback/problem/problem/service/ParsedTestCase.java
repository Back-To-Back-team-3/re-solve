package com.backtoback.problem.problem.service;

import java.util.List;

/**
 * 검증된 테스트의 선언 순서 인자와 기대값을 담는 서비스 결과다. (API 명세서 2.3.0)
 * 정수는 {@code BigInteger}, 소수는 {@code BigDecimal}로 유지하며 중첩 값도 같은 규칙을 따른다.
 * 실행 입력은 음수 0의 부호를 보존하는 원본 JSON 텍스트를 사용한다.
 *
 * @param arguments 함수 명세의 매개변수 선언 순서대로 정렬한 값
 * @param expectedReturn 기대 반환값
 */
public record ParsedTestCase(List<Object> arguments, Object expectedReturn) {
}
