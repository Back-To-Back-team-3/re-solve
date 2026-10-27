package com.backtoback.judge.judging.service.execution.java;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.backtoback.judge.judging.dto.FunctionSpec;
import com.backtoback.judge.judging.dto.TypeSpec;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON 입력을 타입을 보존한 실행용 스트림으로 변환한다.
 * 생성 코드와 쌍으로만 사용하며, 외부 API·S3 저장 형식을 변경하지 않는다.
 */
final class JavaInputEncoder {
    private final JavaTypeMapper types = new JavaTypeMapper();
    private final JsonMapper mapper = JsonMapper.builder().build();

    /**
     * 매개변수 선언 순서로 테스트 입력을 변환한다.
     *
     * @param spec 함수 명세
     * @param arguments 매개변수 이름별 JSON 텍스트
     * @return 실행 템플릿이 해석할 Base64 입력
     * @throws IllegalArgumentException 입력 이름·타입·숫자 범위가 일치하지 않는 경우
     */
    String encode(FunctionSpec spec, Map<String, String> arguments) {
        Set<String> names = spec.parameters().stream().map(FunctionSpec.Parameter::name).collect(Collectors.toSet());
        if (arguments == null || !names.equals(arguments.keySet())) {
            throw new IllegalArgumentException("입력 이름이 함수 명세와 일치하지 않습니다.");
        }
        try (var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes)) {
            for (var parameter : spec.parameters()) {
                String text = arguments.get(parameter.name());
                if (text == null || text.isBlank()) {
                    throw new IllegalArgumentException("입력 JSON 텍스트가 필요합니다.");
                }
                write(output, parameter.type(), readInput(text));
            }
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (JacksonException | ArithmeticException exception) {
            // 예외 원문에 테스트 입력이 포함될 수 있으므로 호출자에게 전달하지 않는다.
            throw new IllegalArgumentException("입력 JSON 또는 숫자 범위가 올바르지 않습니다.");
        } catch (IOException exception) {
            throw new IllegalStateException("Java 실행 입력을 생성할 수 없습니다.");
        }
    }

    /**
     * JSON 값 하나를 읽는다. 뒤에 다른 값이 있으면 거절한다.
     *
     * @param text 입력 JSON 텍스트
     * @return 정밀도와 실수 음수 0을 보존한 값
     * @throws JacksonException JSON 형식이 잘못된 경우
     * @throws IllegalArgumentException 값이 없거나 여러 값이 이어지는 경우
     */
    private JsonNode readInput(String text) {
        // 숫자 표기의 길이로 유효한 입력을 거절하지 않도록 현재 JSON 길이를 토큰 한도로 사용한다.
        var factory
            = JsonFactory
                .builder()
                .streamReadConstraints(StreamReadConstraints.builder().maxNumberLength(text.length()).build())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        try (JsonParser parser = factory.createParser(ObjectReadContext.empty(), text)) {
            parser.nextToken();
            JsonNode value = readNode(parser);
            require(parser.nextToken() == null);
            return value;
        }
    }

    /**
     * JSON 토큰을 트리로 변환한다. 큰 정수·소수의 정밀도를 유지하면서 음수 0을 구분한다.
     *
     * @param parser 현재 값을 가리키는 JSON 파서
     * @return 현재 값과 중첩 구조
     * @throws JacksonException JSON 형식이 잘못된 경우
     * @throws IllegalArgumentException 값이 아닌 토큰이 들어온 경우
     */
    private JsonNode readNode(JsonParser parser) {
        require(parser.currentToken() != null);
        var nodes = mapper.getNodeFactory();
        return switch (parser.currentToken()) {
            case START_ARRAY -> {
                var array = nodes.arrayNode();
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    array.add(readNode(parser));
                }
                yield array;
            }
            case START_OBJECT -> {
                var object = nodes.objectNode();
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    require(parser.currentToken() == JsonToken.PROPERTY_NAME);
                    String name = parser.currentName();
                    parser.nextToken();
                    object.set(name, readNode(parser));
                }
                yield object;
            }
            case VALUE_NUMBER_INT -> nodes.numberNode(parser.getBigIntegerValue());
            case VALUE_NUMBER_FLOAT -> {
                BigDecimal decimal = parser.getDecimalValue();
                // BigDecimal은 음수 0의 부호를 잃으므로 원문 부호를 읽을 수 있을 때 보존한다.
                yield decimal.signum() == 0 && parser.getString().startsWith("-") ? nodes.numberNode(-0.0d)
                    : nodes.numberNode(decimal);
            }
            case VALUE_STRING -> nodes.stringNode(parser.getString());
            case VALUE_TRUE -> nodes.booleanNode(true);
            case VALUE_FALSE -> nodes.booleanNode(false);
            case VALUE_NULL -> nodes.nullNode();
            default -> throw new IllegalArgumentException("입력 JSON 값이 올바르지 않습니다.");
        };
    }

    /**
     * 타입별 값을 기록한다. 태그 값은 {@code judging/java/execution.template}의 {@code readValue}와 함께 유지한다.
     *
     * @param output 실행 입력 스트림
     * @param type 선언 타입
     * @param value 파싱한 JSON 값
     * @throws IOException 메모리 스트림 기록이 실패한 경우
     */
    private void write(DataOutputStream output, TypeSpec type, JsonNode value) throws IOException {
        require(value != null && !value.isNull() && !value.isMissingNode());
        switch (type.kind()) {
            case INT32 -> {
                require(value.isIntegralNumber());
                output.writeByte(1);
                output.writeInt(value.bigIntegerValue().intValueExact());
            }
            case INT64 -> {
                require(value.isIntegralNumber());
                output.writeByte(2);
                output.writeLong(value.bigIntegerValue().longValueExact());
            }
            case FLOAT64 -> {
                require(value.isNumber());
                BigDecimal decimal = value.decimalValue();
                double number = value.doubleValue();
                require(Double.isFinite(number) && (number != 0 || decimal.signum() == 0));
                output.writeByte(3);
                output.writeDouble(number);
            }
            case STRING -> {
                require(value.isString());
                output.writeByte(4);
                writeText(output, value.stringValue());
            }
            case BOOLEAN -> {
                require(value.isBoolean());
                output.writeByte(5);
                output.writeBoolean(value.booleanValue());
            }
            case ARRAY -> writeArray(output, type, value);
            case OBJECT -> {
                require(value.isObject() && value.propertyNames().equals(type.properties().keySet()));
                output.writeByte(7);
                output.writeInt(type.properties().size());
                for (var property : type.properties().entrySet()) {
                    writeText(output, property.getKey());
                    write(output, property.getValue(), value.get(property.getKey()));
                }
            }
            case FLOAT32 -> {
                require(value.isNumber());
                BigDecimal decimal = value.decimalValue();
                // double을 거치지 않고 32비트로 변환해 중간 반올림을 피한다.
                float number = value.floatValue();
                require(Float.isFinite(number) && (number != 0 || decimal.signum() == 0));
                output.writeByte(8);
                output.writeFloat(number);
            }
            default -> throw new IllegalArgumentException("지원하지 않는 입력 타입입니다.");
        }
    }

    /**
     * 배열 방식에 따라 원소를 검증하고 순서대로 기록한다.
     *
     * @param output 실행 입력 스트림
     * @param type 배열 명세
     * @param value 입력 배열
     * @throws IOException 메모리 스트림 기록이 실패한 경우
     */
    private void writeArray(DataOutputStream output, TypeSpec type, JsonNode value) throws IOException {
        require(value.isArray());
        if (type.positionTypes() != null) {
            require(value.size() == type.positionTypes().size());
        } else if (type.repeatTypes() != null) {
            require(value.size() % type.repeatTypes().size() == 0);
        }
        output.writeByte(6);
        writeText(output, type.elementType() == null ? "java.lang.Object" : types.runtimeType(type.elementType()));
        output.writeInt(value.size());
        for (int i = 0; i < value.size(); i++) {
            TypeSpec member = type.elementType();
            if (member == null) {
                member
                    = type.positionTypes() != null ? type.positionTypes().get(i)
                        : type.repeatTypes().get(i % type.repeatTypes().size());
            }
            write(output, member, value.get(i));
        }
    }

    /**
     * 문자열 길이와 UTF-16 코드 단위를 기록한다. {@code writeUTF}의 64KiB 제한을 적용하지 않는다.
     *
     * @param output 실행 입력 스트림
     * @param text 변환할 문자열
     * @throws IOException 메모리 스트림 기록이 실패한 경우
     */
    private void writeText(DataOutputStream output, String text) throws IOException {
        output.writeInt(text.length());
        output.writeChars(text);
    }

    /**
     * 입력 계약 위반을 원문 없는 오류로 거절한다.
     *
     * @param condition 입력이 명세와 일치하는지 여부
     * @throws IllegalArgumentException 일치하지 않는 경우
     */
    private void require(boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException("입력 타입 또는 구조가 함수 명세와 일치하지 않습니다.");
        }
    }
}
