package com.backtoback.judge.judging.service.execution.java;

import com.backtoback.judge.judging.dto.TypeSpec;
import com.backtoback.judge.judging.dto.TypeSpec.Kind;

/** Java 함수 선언과 입력 배열 복원에 사용할 타입을 결정한다. */
final class JavaTypeMapper {
    /**
     * 명세를 Java 소스의 타입으로 변환한다. 동일 타입 배열은 원시 타입 배열을 유지한다.
     *
     * @param type 고정 회차의 타입
     * @return Java 타입 표현
     * @throws IllegalArgumentException 명세 구조가 잘못된 경우
     */
    String sourceType(TypeSpec type) {
        validate(type);
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

    /**
     * 생성기가 사용할 타입 구조를 중첩된 원소와 필드까지 확인한다.
     *
     * @param type 확인할 타입
     * @throws IllegalArgumentException 구조가 잘못된 경우
     */
    private void validate(TypeSpec type) {
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
                validate(type.elementType());
            } else {
                var members = type.positionTypes() != null ? type.positionTypes() : type.repeatTypes();
                if (type.repeatTypes() != null && members.isEmpty()) {
                    throw new IllegalArgumentException("반복 타입 패턴은 비어 있을 수 없습니다.");
                }
                members.forEach(this::validate);
            }
        } else if (type.kind() == Kind.OBJECT) {
            if (arrays != 0 || type.properties() == null
                || type.properties().keySet().stream().anyMatch(key -> key == null)) {
                throw new IllegalArgumentException("객체 필드 명세가 필요합니다.");
            }
            type.properties().values().forEach(this::validate);
        } else if (arrays != 0 || type.properties() != null) {
            throw new IllegalArgumentException("기본 타입에 중첩 명세를 지정할 수 없습니다.");
        }
    }
}
