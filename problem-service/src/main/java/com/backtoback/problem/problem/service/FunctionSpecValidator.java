package com.backtoback.problem.problem.service;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.dto.FunctionSpec;
import com.backtoback.problem.problem.dto.TypeSpec;

/**
 * 함수 구조와 중첩 타입 명세를 검사한다. 언어 예약어 검사는 Judge가 담당한다. (API 명세서 2.3.0)
 */
@Component
public class FunctionSpecValidator {
    private static final Pattern PARAMETER_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * 함수 이름·매개변수 이름과 순서·중첩 타입 명세를 검증한다.
     *
     * @param spec 관리자가 입력한 함수 명세
     * @throws ProblemException 함수 이름·매개변수 또는 타입 구성이 잘못된 경우 {@code COMMON_INVALID_REQUEST}
     */
    public void validate(FunctionSpec spec) {
        // 매개변수가 없는 함수도 null 대신 빈 목록으로 표현한다.
        if (spec == null || !"solution".equals(spec.name()) || spec.parameters() == null) {
            throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        Set<String> names = new HashSet<>();
        for (var parameter : spec.parameters()) {
            if (parameter == null || parameter.name() == null || !PARAMETER_NAME.matcher(parameter.name()).matches()
                || !names.add(parameter.name())) {
                throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
            }
            validateType(parameter.type());
        }
        validateType(spec.returnType());
    }

    /**
     * 타입 종류에 맞는 하위 필드만 지정했는지 재귀적으로 검증한다.
     *
     * @param type 검증할 타입 명세
     * @throws ProblemException 타입 또는 하위 필드 구성이 잘못된 경우 {@code COMMON_INVALID_REQUEST}
     */
    private void validateType(TypeSpec type) {
        if (type == null || type.kind() == null) {
            throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        // 배열 명세 방식이 몇 개 지정됐는지 센다. 빈 목록도 null이 아니므로 지정한 것으로 본다.
        int arrayModes
            = (type.elementType() == null ? 0 : 1) + (type.positionTypes() == null ? 0 : 1)
                + (type.repeatTypes() == null ? 0 : 1);
        switch (type.kind()) {
            case ARRAY -> {
                if (arrayModes != 1 || type.properties() != null) {
                    throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
                }
                if (type.elementType() != null) {
                    validateType(type.elementType());
                } else if (type.positionTypes() != null) {
                    // 빈 위치 목록은 길이 0인 배열 명세로 허용한다. (TypeSpec 공통 타입 명세 §2)
                    type.positionTypes().forEach(this::validateType);
                } else {
                    if (type.repeatTypes().isEmpty()) {
                        throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
                    }
                    type.repeatTypes().forEach(this::validateType);
                }
            }
            case OBJECT -> {
                if (arrayModes != 0 || type.properties() == null
                    || type.properties().keySet().stream().anyMatch(Objects::isNull)) {
                    throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
                }
                type.properties().values().forEach(this::validateType);
            }
            default -> {
                if (arrayModes != 0 || type.properties() != null) {
                    throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
                }
            }
        }
    }
}
