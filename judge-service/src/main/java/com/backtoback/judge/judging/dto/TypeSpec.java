package com.backtoback.judge.judging.dto;

import java.util.List;
import java.util.Map;

/**
 * 함수 입력·반환값의 재귀 타입 명세다. 배열의 세 방식은 원소 순서를 유지한다. (API 명세서 2.3.0)
 *
 * @param kind 값의 타입
 * @param elementType 동일 타입 배열의 원소 타입
 * @param positionTypes 고정 길이 배열의 위치별 타입
 * @param repeatTypes 가변 길이 배열의 반복 타입 패턴
 * @param properties 객체의 필드별 타입
 */
public record TypeSpec(
    Kind kind,
    TypeSpec elementType,
    List<TypeSpec> positionTypes,
    List<TypeSpec> repeatTypes,
    Map<String, TypeSpec> properties
) {
    public enum Kind {
        INT32, INT64, FLOAT32, FLOAT64, STRING, BOOLEAN, ARRAY, OBJECT
    }
}
