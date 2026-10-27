package com.backtoback.notification.preference.dto.request;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * JSON boolean 토큰({@code true}, {@code false})과 {@code null}만 허용하고
 * 문자열({@code "false"})이나 숫자 등의 스칼라 강제 형변환(Coercion)을 거부하는 역직렬화기.
 */
public class StrictBooleanDeserializer extends ValueDeserializer<Boolean> {

    @Override
    public Boolean deserialize(JsonParser parser, DeserializationContext context) {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.VALUE_TRUE) {
            return Boolean.TRUE;
        }
        if (token == JsonToken.VALUE_FALSE) {
            return Boolean.FALSE;
        }
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }
        return (Boolean)context.handleUnexpectedToken(Boolean.class, parser);
    }
}
