package com.backtoback.member.skill.event;

import java.util.Set;

import tools.jackson.databind.JsonNode;

/**
 * 받은 이벤트 payload의 필드를 계약대로 읽는다. 계약과 다르면 예외로 재전달·DLQ에 맡긴다.
 */
final class EventFields {

    static final Set<String> VERDICTS = Set.of("AC", "WA", "TLE", "MLE", "RE", "CE");

    private EventFields() {}

    static String text(JsonNode data, String field) {
        JsonNode value = data.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new IllegalArgumentException("Missing field: " + field);
        }
        return value.asString();
    }

    static int integer(JsonNode data, String field) {
        JsonNode value = data.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException("Invalid field: " + field);
        }
        return value.asInt();
    }

    static long id(JsonNode data, String field) {
        JsonNode value = data.get(field);
        if (value == null) {
            throw new IllegalArgumentException("Missing field: " + field);
        }
        return parseId(value, field);
    }

    // 계약상 ID는 숫자 문자열이다. Long 범위(18자리 이하)만 받는다.
    static long parseId(JsonNode value, String field) {
        if (!value.isString() || !value.asString().matches("[1-9][0-9]{0,17}")) {
            throw new IllegalArgumentException("Invalid id: " + field);
        }
        return Long.parseLong(value.asString());
    }
}
