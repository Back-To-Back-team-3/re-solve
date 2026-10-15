package com.backtoback.problem.problem.service;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.backtoback.problem.global.exception.custom.CommonInvalidRequestException;
import com.backtoback.problem.problem.dto.FunctionSpec;
import com.backtoback.problem.problem.dto.TypeSpec;

@Component
public class FunctionSpecValidator {
    private static final Pattern PARAMETER_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * 함수 이름·매개변수 이름과 순서·중첩 타입 명세를 검증한다.
     *
     * @param spec 관리자가 입력한 함수 명세
     * @throws CommonInvalidRequestException 함수 이름·매개변수 또는 타입 구성이 유효하지 않은 경우
     */
    public void validate(FunctionSpec spec) {
        // 함수 이름은 solution으로 고정한다. 매개변수가 없는 경우에도 null 대신 빈 목록을 받는다.
        if (spec == null || !"solution".equals(spec.name()) || spec.parameters() == null) {
            throw new CommonInvalidRequestException();
        }
        Set<String> names = new HashSet<>();
        for (var parameter : spec.parameters()) {
            // 이름은 영문·밑줄로 시작하고 이후에는 숫자도 허용한다. 누락·형식 오류를 먼저 거른다.
            // Set.add()는 이미 있는 이름이면 false를 반환하므로 같은 함수의 중복 이름도 거부한다.
            if (parameter == null || parameter.name() == null || !PARAMETER_NAME.matcher(parameter.name()).matches()
                || !names.add(parameter.name())) {
                throw new CommonInvalidRequestException();
            }
            // 입력 타입이 배열·객체라면 내부에 선언된 타입까지 함께 검사한다.
            validateType(parameter.type());
        }
        // 반환값도 입력값과 같은 타입 명세 규칙을 적용한다.
        validateType(spec.returnType());
    }

    /**
     * 타입 종류에 맞는 하위 필드만 지정했는지 재귀적으로 검증한다.
     *
     * @param type 검증할 타입 명세
     * @throws CommonInvalidRequestException 타입 또는 하위 필드 구성이 유효하지 않은 경우
     */
    private void validateType(TypeSpec type) {
        // 타입 종류가 있어야 아래에서 해당 종류에 맞는 하위 필드를 검사할 수 있다.
        if (type == null || type.kind() == null) {
            throw new CommonInvalidRequestException();
        }
        // 배열 명세 방식이 몇 개 지정됐는지 센다. 빈 목록도 null이 아니므로 지정한 것으로 본다.
        int arrayModes
            = (type.elementType() == null ? 0 : 1) + (type.positionTypes() == null ? 0 : 1)
                + (type.repeatTypes() == null ? 0 : 1);
        switch (type.kind()) {
            case ARRAY -> {
                // 동일 타입·위치별 타입·반복 타입 중 하나만 선택하고, 객체용 properties는 사용하지 않는다.
                if (arrayModes != 1 || type.properties() != null) {
                    throw new CommonInvalidRequestException();
                }
                if (type.elementType() != null) {
                    // 모든 원소가 공유하는 타입 하나를 검사한다. 중첩된 타입도 재귀 호출로 검사된다.
                    validateType(type.elementType());
                } else if (type.positionTypes() != null) {
                    // 각 위치의 타입을 검사한다. 빈 목록은 길이가 0인 배열 명세이므로 허용한다.
                    type.positionTypes().forEach(this::validateType);
                } else {
                    // 앞에서 정확히 한 방식을 요구했으므로 여기서는 repeatTypes가 존재한다.
                    // 반복할 패턴 자체는 비어 있을 수 없다. 실제 테스트 배열의 길이는 여기서 검사하지 않는다.
                    if (type.repeatTypes().isEmpty()) {
                        throw new CommonInvalidRequestException();
                    }
                    type.repeatTypes().forEach(this::validateType);
                }
            }
            case OBJECT -> {
                // 객체는 이름별 타입 목록이 필요하고 배열용 필드는 사용할 수 없다. null 필드 이름도 거부한다.
                if (arrayModes != 0 || type.properties() == null
                    || type.properties().keySet().stream().anyMatch(Objects::isNull)) {
                    throw new CommonInvalidRequestException();
                }
                // 각 필드의 타입을 재귀적으로 검사한다. 빈 properties는 빈 객체 명세이므로 허용한다.
                type.properties().values().forEach(this::validateType);
            }
            default -> {
                // 정수·소수·문자열·불리언은 kind만 사용하므로 배열·객체용 하위 필드를 거부한다.
                if (arrayModes != 0 || type.properties() != null) {
                    throw new CommonInvalidRequestException();
                }
            }
        }
    }
}
