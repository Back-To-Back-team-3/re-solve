package com.backtoback.problem.problem.dto;

import java.util.List;

/**
 * 호출할 함수의 이름과 매개변수 선언 순서 및 반환 타입을 정의한다.
 *
 * @param name solution으로 고정된 함수 이름
 * @param parameters 호출 순서대로 선언한 매개변수 목록
 * @param returnType 반환값 하나의 타입
 */
public record FunctionSpec(String name, List<Parameter> parameters, TypeSpec returnType) {
    /**
     * 매개변수 하나의 이름과 타입을 정의한다.
     *
     * @param name 테스트 입력의 키와 대응하는 매개변수 이름
     * @param type 매개변수의 타입
     */
    public record Parameter(String name, TypeSpec type) {
    }
}
