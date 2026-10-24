package com.backtoback.judge.judging.service.execution.cpp;

import com.backtoback.judge.judging.dto.TypeSpec;
import com.backtoback.judge.judging.service.execution.FunctionSpecValidator;

/** 공통 타입을 C++17의 기본 숫자 타입·표준 컨테이너·{@code Value}로 표현한다. */
final class CppTypeMapper {
    private final FunctionSpecValidator validator = new FunctionSpecValidator();

    /**
     * 함수 선언에 사용할 C++ 타입을 생성한다.
     *
     * @param type 선언 타입
     * @return 표준 라이브러리 기반의 C++ 타입
     * @throws IllegalArgumentException 타입 구조가 잘못된 경우
     */
    String sourceType(TypeSpec type) {
        validator.validateType(type);
        return switch (type.kind()) {
            case INT32 -> "int";
            case INT64 -> "long long";
            case FLOAT32 -> "float";
            case FLOAT64 -> "double";
            case STRING -> "::std::string";
            case BOOLEAN -> "bool";
            case OBJECT -> "::std::map<::std::string, ::Value>";
            case ARRAY ->
                "::std::vector<" + (type.elementType() == null ? "::Value" : sourceType(type.elementType())) + ">";
        };
    }
}
