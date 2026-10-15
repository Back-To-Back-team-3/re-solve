package com.backtoback.problem.problem.dto;

import java.util.List;

/**
 * 검증된 테스트의 실행 인자와 기대값을 표현한다.
 * 정수는 BigInteger, 소수는 BigDecimal로 유지하며 배열·객체의 내부 값도 같은 규칙을 따른다.
 *
 * @param arguments 함수 명세의 매개변수 선언 순서대로 정렬한 값
 * @param expectedReturn 기대 반환값
 */
public record ParsedTestCase(List<Object> arguments, Object expectedReturn) {
}
