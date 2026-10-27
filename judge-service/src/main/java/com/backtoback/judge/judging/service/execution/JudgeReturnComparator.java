package com.backtoback.judge.judging.service.execution;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.backtoback.judge.judging.dto.TypeSpec;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;

/**
 * 선언 타입으로 JSON 반환값을 정규화해 허용 오차 없이 비교한다. ({@code TypeSpec} 공통 타입 명세 §2·§3·§7)
 */
@Service
public final class JudgeReturnComparator {
    private final FunctionSpecValidator validator = new FunctionSpecValidator();

    /**
     * 기대값과 실제 반환값의 선언 타입·구조·값이 일치하는지 비교한다.
     * 잘못된 고정 회차 데이터는 사용자 오답으로 바꾸지 않고 호출자에게 전달한다.
     *
     * @param type 반환 타입 명세
     * @param expectedText 고정 회차의 기대 JSON 텍스트
     * @param actualText 정상 실행된 실제 JSON 텍스트; 누락되면 불일치
     * @return 두 값이 모두 유효하며 정규화한 값이 같은지 여부
     * @throws IllegalArgumentException 명세 또는 기대값이 유효하지 않은 경우
     */
    public boolean matches(TypeSpec type, String expectedText, String actualText) {
        validator.validateType(type);
        Object expected = normalize(type, expectedText);
        try {
            return expected.equals(normalize(type, actualText));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * JSON 값 하나를 선언 타입으로 읽고 뒤에 다른 값이 없는지 확인한다.
     *
     * @param type 검증된 반환 타입
     * @param text 정규화할 JSON 텍스트
     * @return 타입·정밀도를 보존한 비교용 값
     * @throws IllegalArgumentException JSON 형식·타입·범위가 잘못된 경우
     */
    private Object normalize(TypeSpec type, String text) {
        require(text != null);
        // 출력 크기 제한은 판정기가 검사하므로 숫자 토큰에 별도의 짧은 길이 제한을 두지 않는다.
        var factory
            = JsonFactory
                .builder()
                .streamReadConstraints(
                    StreamReadConstraints.builder().maxNumberLength(Math.max(1, text.length())).build()
                )
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        try (JsonParser parser = factory.createParser(ObjectReadContext.empty(), text)) {
            parser.nextToken();
            Object value = readValue(type, parser);
            require(parser.nextToken() == null);
            return value;
        } catch (JacksonException | ArithmeticException exception) {
            // 파서 예외 원문에는 숨김 테스트의 기대값이나 사용자 반환값이 포함될 수 있다.
            throw new IllegalArgumentException("반환 JSON의 타입·구조·숫자 범위가 올바르지 않습니다.");
        }
    }

    /**
     * 현재 토큰을 선언 타입에 맞게 검사하고 정규화한다.
     *
     * @param type 현재 위치의 타입
     * @param parser 현재 값을 가리키는 파서
     * @return 비교용 기본 값·배열·객체
     * @throws IllegalArgumentException 값이 타입과 일치하지 않는 경우
     */
    private Object readValue(TypeSpec type, JsonParser parser) {
        require(parser.currentToken() != null);
        return switch (type.kind()) {
            case INT32 -> {
                require(parser.currentToken() == JsonToken.VALUE_NUMBER_INT);
                yield parser.getBigIntegerValue().intValueExact();
            }
            case INT64 -> {
                require(parser.currentToken() == JsonToken.VALUE_NUMBER_INT);
                yield parser.getBigIntegerValue().longValueExact();
            }
            case FLOAT32, FLOAT64 -> readFloating(type, parser);
            case STRING -> {
                require(parser.currentToken() == JsonToken.VALUE_STRING);
                yield parser.getString();
            }
            case BOOLEAN -> {
                require(
                    parser.currentToken() == JsonToken.VALUE_TRUE || parser.currentToken() == JsonToken.VALUE_FALSE
                );
                yield parser.currentToken() == JsonToken.VALUE_TRUE;
            }
            case ARRAY -> readArray(type, parser);
            case OBJECT -> readObject(type, parser);
        };
    }

    /**
     * 원본 수치 표기에서 선언 정밀도로 직접 변환하고 범위 초과·언더플로를 거절한다.
     *
     * @param type 부동소수 선언 타입
     * @param parser 현재 숫자를 가리키는 파서
     * @return 정규화한 숫자; 두 부호의 0은 같은 값
     * @throws IllegalArgumentException 유한한 선언 타입 값으로 표현할 수 없는 경우
     */
    private Object readFloating(TypeSpec type, JsonParser parser) {
        require(
            parser.currentToken() == JsonToken.VALUE_NUMBER_INT || parser.currentToken() == JsonToken.VALUE_NUMBER_FLOAT
        );
        BigDecimal decimal = parser.getDecimalValue();
        if (type.kind() == TypeSpec.Kind.FLOAT32) {
            // binary64를 거치면 중간 반올림으로 binary32 결과가 달라질 수 있다.
            float number = Float.parseFloat(parser.getString());
            require(Float.isFinite(number) && (number != 0 || decimal.signum() == 0));
            return number == 0 ? 0.0f : number;
        }
        double number = Double.parseDouble(parser.getString());
        require(Double.isFinite(number) && (number != 0 || decimal.signum() == 0));
        return number == 0 ? 0.0d : number;
    }

    /**
     * 배열의 순서·길이·위치별 타입을 유지한 비교용 목록을 만든다.
     *
     * @param type 배열 명세
     * @param parser 배열 시작 토큰을 가리키는 파서
     * @return 선언 타입으로 정규화한 원소 목록
     * @throws IllegalArgumentException 배열 방식·길이·원소가 명세와 다른 경우
     */
    private List<Object> readArray(TypeSpec type, JsonParser parser) {
        require(parser.currentToken() == JsonToken.START_ARRAY);
        var values = new ArrayList<Object>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            TypeSpec element;
            if (type.elementType() != null) {
                element = type.elementType();
            } else if (type.positionTypes() != null) {
                require(values.size() < type.positionTypes().size());
                element = type.positionTypes().get(values.size());
            } else {
                element = type.repeatTypes().get(values.size() % type.repeatTypes().size());
            }
            values.add(readValue(element, parser));
        }
        require(type.positionTypes() == null || values.size() == type.positionTypes().size());
        require(type.repeatTypes() == null || values.size() % type.repeatTypes().size() == 0);
        return values;
    }

    /**
     * 필드 집합을 정확히 확인하고 키 순서에 독립적인 객체를 만든다.
     *
     * @param type 객체 명세
     * @param parser 객체 시작 토큰을 가리키는 파서
     * @return 필드별로 정규화한 객체
     * @throws IllegalArgumentException 필드가 추가·누락되거나 필드 값이 유효하지 않은 경우
     */
    private Map<String, Object> readObject(TypeSpec type, JsonParser parser) {
        require(parser.currentToken() == JsonToken.START_OBJECT);
        var values = new HashMap<String, Object>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            require(parser.currentToken() == JsonToken.PROPERTY_NAME);
            String name = parser.currentName();
            require(type.properties().containsKey(name));
            parser.nextToken();
            values.put(name, readValue(type.properties().get(name), parser));
        }
        require(values.keySet().equals(type.properties().keySet()));
        return values;
    }

    /**
     * 값 검증 실패를 입력 원문 없는 예외로 전달한다.
     *
     * @param condition 통과해야 하는 조건
     * @throws IllegalArgumentException 조건을 만족하지 않는 경우
     */
    private void require(boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException("반환 JSON의 타입·구조·숫자 범위가 올바르지 않습니다.");
        }
    }
}
