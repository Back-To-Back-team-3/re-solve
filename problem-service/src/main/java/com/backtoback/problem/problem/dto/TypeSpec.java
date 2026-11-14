package com.backtoback.problem.problem.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 입력값 또는 반환값 하나의 공통 타입을 정의한다.
 *
 * @param kind 지원 타입
 * @param elementType 동일 타입 배열의 원소 타입
 * @param positionTypes 고정 길이 배열의 위치별 타입
 * @param repeatTypes 가변 길이 배열에서 반복할 타입 패턴
 * @param properties 고정 필드 객체의 이름별 타입
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TypeSpec(
    /*
     * 기본 타입은 kind만 지정한다.
     * 명세: {"kind":"INT32"}
     * 값: 123
     * ARRAY는 아래 세 방식 중 정확히 하나를 사용하며, 모든 방식에서 원소 순서를 유지한다.
     */
    Kind kind,

    /*
     * 동일 타입 배열: 모든 원소에 같은 타입을 적용하고 길이는 고정하지 않는다.
     * 명세: {"kind":"ARRAY","elementType":{"kind":"INT32"}}
     * 허용: [1, 2, 3], [1], []
     * 거부: [1, "a"]
     */
    TypeSpec elementType,

    /*
     * 위치별 타입 배열: 각 위치의 타입과 배열 길이를 고정한다.
     * 명세: {"kind":"ARRAY","positionTypes":[{"kind":"INT32"},{"kind":"STRING"}]}
     * 허용: [1, "a"]
     * 거부: ["a", 1], [1], [1, "a", 2]
     */
    List<TypeSpec> positionTypes,

    /*
     * 반복 타입 배열: 지정한 타입 패턴을 순서대로 반복한다.
     * 명세: {"kind":"ARRAY","repeatTypes":[{"kind":"INT32"},{"kind":"STRING"}]}
     * 허용: [1, "a"], [1, "a", 2, "b"], []
     * 거부: [1, "a", 2] — 마지막 패턴이 완성되지 않음
     * 패턴 자체는 비어 있을 수 없고, 실제 배열 길이는 패턴 길이의 배수여야 한다.
     */
    List<TypeSpec> repeatTypes,

    /*
     * 고정 필드 객체: 필드 이름마다 타입을 지정한다. 키 나열 순서는 무관하다.
     * 명세: {"kind":"OBJECT","properties":{"name":{"kind":"STRING"},"age":{"kind":"INT32"}}}
     * 허용: {"name":"kim","age":20}, {"age":20,"name":"kim"}
     * 거부: {"name":"kim"}, {"name":"kim","age":20,"extra":true}
     * 선언한 필드는 모두 필수이고, 추가 필드나 null 값은 허용하지 않는다.
     * 배열 원소와 객체 필드에도 TypeSpec을 사용하므로 배열·객체를 중첩할 수 있다.
     */
    Map<String, TypeSpec> properties
) {
    public enum Kind {
        INT32, INT64, FLOAT32, FLOAT64, STRING, BOOLEAN, ARRAY, OBJECT
    }
}

/*
 * 조합 예시
 *
 * 1. 단일 값 — int
 * 실제 값: 123
 * 타입 명세:
 * { "kind": "INT32" }
 *
 * 2. 배열 — [int]
 * 실제 값: [123]
 * 타입 명세:
 * {
 *   "kind": "ARRAY",
 *   "elementType": { "kind": "INT32" }
 * }
 *
 * 3. 객체 — {"value": int}
 * 실제 값: {"value": 123}
 * 타입 명세:
 * {
 *   "kind": "OBJECT",
 *   "properties": {
 *     "value": { "kind": "INT32" }
 *   }
 * }
 *
 * 4. 배열 2중첩 — positionTypes로 [정수 배열, 문자열, 정수]의 위치 지정
 * 실제 값: [[123, 456, 789], "hello", 456]
 * 타입 명세:
 * {
 *   "kind": "ARRAY",
 *   "positionTypes": [
 *     {
 *       "kind": "ARRAY",
 *       "elementType": { "kind": "INT32" }
 *     },
 *     { "kind": "STRING" },
 *     { "kind": "INT32" }
 *   ]
 * }
 *
 * 5. 객체 2중첩 — {"nested": {"value": int}, "label": string}
 * 실제 값: {"nested": {"value": 123}, "label": "hello"}
 * 타입 명세:
 * {
 *   "kind": "OBJECT",
 *   "properties": {
 *     "nested": {
 *       "kind": "OBJECT",
 *       "properties": {
 *         "value": { "kind": "INT32" }
 *       }
 *     },
 *     "label": { "kind": "STRING" }
 *   }
 * }
 *
 * 6. 객체 + 배열 중첩 — repeatTypes로 [int, string] 패턴 반복
 * 실제 값: {"values": [123, "hello", 456, "world"], "label": "example"}
 * 타입 명세:
 * {
 *   "kind": "OBJECT",
 *   "properties": {
 *     "values": {
 *       "kind": "ARRAY",
 *       "repeatTypes": [
 *         { "kind": "INT32" },
 *         { "kind": "STRING" }
 *       ]
 *     },
 *     "label": { "kind": "STRING" }
 *   }
 * }
 */
