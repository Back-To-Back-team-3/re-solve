package com.backtoback.problem.problem.dto.deserializer;

import java.util.LinkedHashMap;
import java.util.Map;

import com.backtoback.problem.problem.dto.request.TestCaseRequest;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.json.JsonMapper;

public class TestCaseRequestDeserializer extends ValueDeserializer<TestCaseRequest> {
    // 테스트 객체를 트리로 읽을 때만 중복 키를 거부한다. 공유 매퍼의 설정은 변경하지 않는다.
    private static final ObjectReader READER
        = JsonMapper
            .builder()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            // 부모 요청의 다음 필드·테스트 항목은 정상이다. 문서 끝 검사는 바깥 매퍼가 담당한다.
            .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build()
            .readerFor(JsonNode.class);

    /**
     * 테스트 요청 하나의 중복 키와 JSON 타입을 검사하고 원문 문자열을 보존한다.
     * 누락·null 값은 기존 TestCaseValidator와 TestValueParser에서 검사하도록 유지한다.
     *
     * @param parser 현재 테스트 객체를 읽는 JSON 파서
     * @param context 요청 역직렬화 문맥
     * @return 공개 여부와 입력·기대값 원문을 담은 요청
     * @throws tools.jackson.core.JacksonException 중복 키 또는 잘못된 JSON 타입이 있는 경우
     */
    @Override
    public TestCaseRequest deserialize(JsonParser parser, DeserializationContext context) {
        // DTO 자체를 다시 읽으면 같은 역직렬화기가 호출되므로 JSON 트리로 읽는다.
        // 이 단계에서 현재 테스트 객체와 내부 객체의 중복 키를 검사한다.
        JsonNode root = READER.readValue(parser);
        // 테스트 한 건은 객체여야 한다. reportInputMismatch()는 값을 반환하지 않고 Jackson 예외를 던진다.
        if (!root.isObject()) {
            return context.reportInputMismatch(TestCaseRequest.class, "테스트 케이스는 객체여야 합니다.");
        }
        // get()은 필드 누락 시 null, JSON null이 명시된 경우에는 NullNode를 반환한다.
        // 두 경우 모두 기본값 false로 바꾸지 않고 null로 남겨 TestCaseValidator가 누락을 판단하게 한다.
        Boolean isSample = null;
        JsonNode sample = root.get("isSample");
        if (sample != null && !sample.isNull()) {
            // 문자열 "true"나 숫자 1을 불리언으로 자동 변환하지 않는다.
            if (!sample.isBoolean()) {
                return context.reportInputMismatch(TestCaseRequest.class, "isSample은 불리언이어야 합니다.");
            }
            isSample = sample.booleanValue();
        }
        // 입력 목록도 누락·null과 빈 객체를 구분한다. 매개변수가 없는 함수의 입력은 빈 객체로 표현한다.
        Map<String, String> argumentTexts = null;
        JsonNode arguments = root.get("argumentTexts");
        if (arguments != null && !arguments.isNull()) {
            // 입력은 매개변수 이름을 키로 갖는 객체여야 하며, 배열이나 단일 값은 받지 않는다.
            if (!arguments.isObject()) {
                return context.reportInputMismatch(TestCaseRequest.class, "argumentTexts는 객체여야 합니다.");
            }
            // 읽은 키 순서를 유지한다. 실제 실행 인자의 순서는 이후 함수 명세에 맞춰 정리한다.
            argumentTexts = new LinkedHashMap<>();
            for (var field : arguments.properties()) {
                // 키는 매개변수 이름, 값은 JSON 텍스트 문자열이다. 이름 일치 여부는 이후 검증한다.
                argumentTexts.put(field.getKey(), readText(field.getValue(), context));
            }
        }
        // 기대값도 입력값과 동일하게 문자열로만 읽은 뒤 요청 DTO를 생성한다.
        // 문자열 내부의 JSON 문법·타입·숫자 범위는 TestValueParser가 검사한다.
        return new TestCaseRequest(isSample, argumentTexts, readText(root.get("expectedReturnText"), context));
    }

    /**
     * 자동 타입 변환 없이 JSON 문자열을 읽는다.
     *
     * @param value 입력 또는 기대값 노드
     * @param context 요청 역직렬화 문맥
     * @return 원문 문자열; 누락되거나 JSON null이면 null
     * @throws tools.jackson.core.JacksonException 문자열 이외의 값이 전달된 경우
     */
    private String readText(JsonNode value, DeserializationContext context) {
        // 필수 여부는 이후 검증 단계에 맡긴다. 여기서는 누락·null을 빈 문자열 등으로 대체하지 않는다.
        if (value == null || value.isNull()) {
            return null;
        }
        // 숫자·불리언·배열·객체를 문자열로 자동 변환하면 잘못된 요청 타입을 놓치므로 거부한다.
        if (!value.isString()) {
            return context.reportInputMismatch(TestCaseRequest.class, "테스트 입력과 기대값은 문자열이어야 합니다.");
        }
        // JSON 이스케이프가 해석된 문자열 값을 반환한다. 공백 제거·숫자 변환 등 추가 가공은 하지 않는다.
        return value.stringValue();
    }
}
