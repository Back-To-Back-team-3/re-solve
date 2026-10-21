package com.backtoback.problem.problem.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.backtoback.problem.global.exception.custom.ProblemTestCaseInvalidException;
import com.backtoback.problem.problem.dto.TypeSpec;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

@Component
public class TestValueParser {
    // 애플리케이션의 일반 JSON 변환 설정과 분리해 테스트 숫자의 정밀도를 보존한다.
    private final JsonMapper mapper
        = JsonMapper
            .builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    /**
     * JSON 값 하나를 정밀도 손실 없이 파싱하고 검증된 타입 명세에 따라 변환한다.
     *
     * @param text 입력 또는 기대 반환값의 JSON 텍스트
     * @param type FunctionSpecValidator 검증을 통과한 타입 명세
     * @return 정수·소수·문자열·불리언·불변 리스트 또는 불변 맵
     * @throws ProblemTestCaseInvalidException JSON 문법·타입·숫자 범위 또는 중첩 구조가 유효하지 않은 경우
     */
    public Object parse(String text, TypeSpec type) {
        if (text == null || text.isBlank()) {
            throw new ProblemTestCaseInvalidException();
        }
        try {
            return convert(mapper.readTree(text), type);
        } catch (JacksonException exception) {
            throw new ProblemTestCaseInvalidException();
        }
    }

    /**
     * JSON 노드를 선언 타입으로 검증하고 입력값의 구조와 순서를 보존한다.
     *
     * @param value 파싱된 JSON 값
     * @param type 검증된 타입 명세
     * @return 정밀도와 구조를 보존한 값
     * @throws ProblemTestCaseInvalidException null·타입 불일치 또는 숫자 범위 오류가 있는 경우
     */
    private Object convert(JsonNode value, TypeSpec type) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            throw new ProblemTestCaseInvalidException();
        }
        return switch (type.kind()) {
            case INT32, INT64 -> {
                if (!value.isIntegralNumber()
                    || (type.kind() == TypeSpec.Kind.INT32 ? !value.canConvertToInt() : !value.canConvertToLong())) {
                    throw new ProblemTestCaseInvalidException();
                }
                yield value.bigIntegerValue();
            }
            case FLOAT32, FLOAT64 -> {
                if (!value.isNumber()) {
                    throw new ProblemTestCaseInvalidException();
                }
                BigDecimal decimal = value.decimalValue();
                double converted = type.kind() == TypeSpec.Kind.FLOAT32 ? decimal.floatValue() : decimal.doubleValue();
                if (!Double.isFinite(converted) || (decimal.signum() != 0 && converted == 0)) {
                    throw new ProblemTestCaseInvalidException();
                }
                // 범위 확인에만 부동소수점 변환을 사용하고 실제 값은 BigDecimal로 유지한다.
                yield decimal.stripTrailingZeros();
            }
            case STRING -> {
                if (!value.isString()) {
                    throw new ProblemTestCaseInvalidException();
                }
                yield value.stringValue();
            }
            case BOOLEAN -> {
                if (!value.isBoolean()) {
                    throw new ProblemTestCaseInvalidException();
                }
                yield value.booleanValue();
            }
            case ARRAY -> convertArray(value, type);
            case OBJECT -> convertObject(value, type);
        };
    }

    /**
     * 배열 길이와 위치별 타입을 검증하고 원소 순서대로 변환한다.
     *
     * @param value 파싱된 배열 값
     * @param type 동일 타입·고정 위치 또는 반복 패턴 배열 명세
     * @return 순서를 보존한 불변 배열 값
     * @throws ProblemTestCaseInvalidException 배열 타입·길이 또는 원소 타입이 일치하지 않는 경우
     */
    private List<Object> convertArray(JsonNode value, TypeSpec type) {
        if (!value.isArray() || (type.positionTypes() != null && value.size() != type.positionTypes().size())
            || (type.repeatTypes() != null && value.size() % type.repeatTypes().size() != 0)) {
            throw new ProblemTestCaseInvalidException();
        }
        List<Object> result = new ArrayList<>(value.size());
        for (int index = 0; index < value.size(); index++) {
            TypeSpec element = type.elementType();
            if (element == null) {
                element
                    = type.positionTypes() != null ? type.positionTypes().get(index)
                        : type.repeatTypes().get(index % type.repeatTypes().size());
            }
            result.add(convert(value.get(index), element));
        }
        return List.copyOf(result);
    }

    /**
     * 객체의 필수 키 집합과 각 필드의 타입을 검증하고 변환한다.
     *
     * @param value 파싱된 객체 값
     * @param type 고정 필드 객체 명세
     * @return 필드값을 보존한 불변 객체 값
     * @throws ProblemTestCaseInvalidException 객체의 키 또는 필드 타입이 일치하지 않는 경우
     */
    private Map<String, Object> convertObject(JsonNode value, TypeSpec type) {
        if (!value.isObject() || !type.properties().keySet().equals(new HashSet<>(value.propertyNames()))) {
            throw new ProblemTestCaseInvalidException();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        type.properties().forEach((name, fieldType) -> result.put(name, convert(value.get(name), fieldType)));
        return Collections.unmodifiableMap(result);
    }
}
