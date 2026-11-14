package com.backtoback.judge.judging.service.execution.java;

import com.backtoback.judge.judging.dto.TypeSpec;
import com.backtoback.judge.judging.service.execution.FunctionSpecValidator;

/** Java 함수 선언과 입력 배열 복원에 사용할 타입을 결정한다. */
final class JavaTypeMapper {
    private final FunctionSpecValidator validator = new FunctionSpecValidator();

    /**
     * 명세를 Java 소스의 타입으로 변환한다. 동일 타입 배열은 원시 타입 배열을 유지한다.
     *
     * @param type 고정 회차의 타입
     * @return Java 타입 표현
     * @throws IllegalArgumentException 명세 구조가 잘못된 경우
     */
    String sourceType(TypeSpec type) {
        validator.validateType(type);
        return switch (type.kind()) {
            case INT32 -> "int";
            case INT64 -> "long";
            case FLOAT32 -> "float";
            case FLOAT64 -> "double";
            case STRING -> "String";
            case BOOLEAN -> "boolean";
            case OBJECT -> "java.util.Map<String, Object>";
            case ARRAY -> type.elementType() == null ? "Object[]" : sourceType(type.elementType()) + "[]";
        };
    }

    /**
     * 배열 원소 클래스를 실행 환경에서 복원할 이름으로 변환한다.
     *
     * @param type 원소 타입
     * @return {@code Class.forName}에 사용할 이름 또는 원시 타입 이름
     */
    String runtimeType(TypeSpec type) {
        return switch (type.kind()) {
            case STRING -> "java.lang.String";
            case OBJECT -> "java.util.Map";
            case ARRAY -> {
                if (type.elementType() == null) {
                    yield "[Ljava.lang.Object;";
                }
                String child = runtimeType(type.elementType());
                yield "[" + switch (child) {
                    case "int" -> "I";
                    case "long" -> "J";
                    case "float" -> "F";
                    case "double" -> "D";
                    case "boolean" -> "Z";
                    default -> child.startsWith("[") ? child : "L" + child + ";";
                };
            }
            default -> sourceType(type);
        };
    }

}
