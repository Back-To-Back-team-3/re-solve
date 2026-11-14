package com.backtoback.judge.judging.service.execution.python;

import com.backtoback.judge.judging.dto.TypeSpec;
import com.backtoback.judge.judging.service.execution.FunctionSpecValidator;

/** 공통 타입 명세를 Python 기본 코드의 타입 힌트로 표현한다. */
final class PythonTypeMapper {
    private final FunctionSpecValidator validator = new FunctionSpecValidator();

    /**
     * 타입 구조를 검증하고 Python 표준 타입 이름을 반환한다.
     *
     * @param type 선언한 타입
     * @return Python 타입 힌트
     * @throws IllegalArgumentException 타입 명세가 잘못된 경우
     */
    String sourceType(TypeSpec type) {
        validator.validateType(type);
        return switch (type.kind()) {
            case INT32, INT64 -> "int";
            case FLOAT32, FLOAT64 -> "float";
            case STRING -> "str";
            case BOOLEAN -> "bool";
            case ARRAY -> "list";
            case OBJECT -> "dict";
        };
    }
}
