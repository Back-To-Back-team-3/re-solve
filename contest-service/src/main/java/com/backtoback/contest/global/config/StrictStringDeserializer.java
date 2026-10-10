package com.backtoback.contest.global.config;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * 문자열 계약 필드에서 숫자·boolean JSON의 묵시적 String 변환을 차단한다.
 * null은 NotNull·NotBlank 제약이 처리한다. 다른 DTO의 Jackson 동작을 전역 변경하지 않는다.
 * <p>기준 문서: API 명세서 v1.1 / §0.2 식별자(ID) 규칙, §4.1.1 시험 생성: 문자열 ID·점수.
 */
public class StrictStringDeserializer extends ValueDeserializer<String> {
    /**
     * JSON 문자열 토큰만 받아 원문 문자열로 반환한다.
     *
     * @param parser 현재 필드의 JSON 토큰
     * @param context 변환 실패를 보고할 Jackson 문맥
     * @return 형식을 변환하지 않은 문자열
     * @throws tools.jackson.core.JacksonException 문자열이 아니거나 JSON을 읽을 수 없는 경우
     */
    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_STRING) {
            return context.reportInputMismatch(String.class, "JSON 문자열로 입력해야 합니다.");
        }
        return parser.getString();
    }
}
