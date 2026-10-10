package com.backtoback.contest.global.config;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * 정수 입력에서 실수의 소수부 절삭이나 숫자 문자열 변환을 차단한다.
 * 허용 범위와 null 검증은 입력 DTO의 Bean Validation에 맡긴다.
 * <p>기준 문서: API 명세서 v1.1 / §4.1.1 시험 생성: displayOrder의 integer 계약.
 */
public class StrictIntegerDeserializer extends ValueDeserializer<Integer> {
    /**
     * 정수 JSON 토큰만 받아 int 범위의 값으로 반환한다.
     *
     * @param parser 현재 필드의 JSON 토큰
     * @param context 입력 오류 보고 문맥
     * @return 자동 절삭·문자열 변환하지 않은 정수
     * @throws tools.jackson.core.JacksonException 정수가 아니거나 int 범위를 벗어난 경우
     */
    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            return context.reportInputMismatch(Integer.class, "JSON 정수로 입력해야 합니다.");
        }
        return parser.getIntValue();
    }
}
