package com.backtoback.judge.judging.dto;

import java.util.List;

/**
 * 고정 문제 회차에서 읽은 함수 명세다. Problem의 등록 DTO에 서비스 의존성을 만들지 않는다.
 *
 * @param name 함수명
 * @param parameters 선언 순서의 매개변수
 * @param returnType 반환 타입
 */
public record FunctionSpec(String name, List<Parameter> parameters, TypeSpec returnType) {
    /**
     * 함수 매개변수 하나의 이름과 타입이다.
     *
     * @param name 매개변수 이름
     * @param type 매개변수 타입
     */
    public record Parameter(String name, TypeSpec type) {
    }
}
