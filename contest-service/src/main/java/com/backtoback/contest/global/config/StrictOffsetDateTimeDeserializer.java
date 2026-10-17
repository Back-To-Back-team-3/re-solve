package com.backtoback.contest.global.config;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * 시차를 포함한 ISO 8601 문자열만 시각 입력으로 받는다.
 * 숫자 timestamp나 시차 없는 지역 시각을 묵시적으로 변환하지 않는다.
 * <p>기준 문서: API 명세서 v1.1 / §0.1 기본 규칙, §4.1.1 시험 생성: date-time 문자열 계약.
 */
public class StrictOffsetDateTimeDeserializer extends ValueDeserializer<OffsetDateTime> {
    /**
     * 문자열을 OffsetDateTime으로 읽고 파싱 실패의 원문은 오류 메시지에 노출하지 않는다.
     *
     * @param parser 현재 시각 필드 토큰
     * @param context 입력 오류 보고 문맥
     * @return 시차를 포함한 입력 시각
     * @throws tools.jackson.core.JacksonException 문자열이 아니거나 시차 포함 ISO 시각이 아닌 경우
     */
    @Override
    public OffsetDateTime deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_STRING) {
            return context.reportInputMismatch(OffsetDateTime.class, "시각은 시차를 포함한 ISO 8601 문자열이어야 합니다.");
        }
        try {
            return OffsetDateTime.parse(parser.getString());
        } catch (DateTimeParseException exception) {
            return context.reportInputMismatch(OffsetDateTime.class, "시각은 시차를 포함한 ISO 8601 문자열이어야 합니다.");
        }
    }
}
