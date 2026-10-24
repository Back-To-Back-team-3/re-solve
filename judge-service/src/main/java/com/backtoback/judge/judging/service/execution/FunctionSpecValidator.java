package com.backtoback.judge.judging.service.execution;

import java.util.HashSet;

import com.backtoback.judge.judging.dto.FunctionSpec;
import com.backtoback.judge.judging.dto.TypeSpec;
import com.backtoback.judge.judging.dto.TypeSpec.Kind;

/** 함수 이름·매개변수 구조와 언어 공통 재귀 타입 계약을 검사한다. */
public final class FunctionSpecValidator {
    /**
     * 함수 구조와 모든 입력·반환 타입을 검사한다. 언어 예약어는 각 생성기에서 검사한다.
     *
     * @param spec 검사할 고정 회차의 함수 명세
     * @throws IllegalArgumentException 명세가 없거나 구조가 잘못된 경우
     */
    public void validate(FunctionSpec spec) {
        if (spec == null || !"solution".equals(spec.name()) || spec.parameters() == null) {
            throw new IllegalArgumentException("solution 함수 명세가 필요합니다.");
        }
        var names = new HashSet<String>();
        for (var parameter : spec.parameters()) {
            if (parameter == null || parameter.name() == null || !parameter.name().matches("[A-Za-z_][A-Za-z0-9_]*")
                || !names.add(parameter.name())) {
                throw new IllegalArgumentException("매개변수 이름이 올바르지 않습니다.");
            }
            validateType(parameter.type());
        }
        validateType(spec.returnType());
    }

    /**
     * 기본 타입·배열 세 방식·고정 필드 객체를 중첩된 원소까지 검사한다.
     *
     * @param type 확인할 타입
     * @throws IllegalArgumentException 타입 구조가 잘못된 경우
     */
    public void validateType(TypeSpec type) {
        if (type == null || type.kind() == null) {
            throw new IllegalArgumentException("함수 타입이 필요합니다.");
        }
        int arrays
            = (type.elementType() == null ? 0 : 1) + (type.positionTypes() == null ? 0 : 1)
                + (type.repeatTypes() == null ? 0 : 1);
        if (type.kind() == Kind.ARRAY) {
            if (arrays != 1 || type.properties() != null) {
                throw new IllegalArgumentException("배열 타입은 한 가지 방식으로 지정해야 합니다.");
            }
            if (type.elementType() != null) {
                validateType(type.elementType());
            } else {
                var members = type.positionTypes() != null ? type.positionTypes() : type.repeatTypes();
                if (type.repeatTypes() != null && members.isEmpty()) {
                    throw new IllegalArgumentException("반복 타입 패턴은 비어 있을 수 없습니다.");
                }
                members.forEach(this::validateType);
            }
        } else if (type.kind() == Kind.OBJECT) {
            if (arrays != 0 || type.properties() == null
                || type.properties().keySet().stream().anyMatch(key -> key == null)) {
                throw new IllegalArgumentException("객체 필드 명세가 필요합니다.");
            }
            type.properties().values().forEach(this::validateType);
        } else if (arrays != 0 || type.properties() != null) {
            throw new IllegalArgumentException("기본 타입에 중첩 명세를 지정할 수 없습니다.");
        }
    }
}
