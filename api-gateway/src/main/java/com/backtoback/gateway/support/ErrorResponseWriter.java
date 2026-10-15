package com.backtoback.gateway.support;

import java.nio.charset.StandardCharsets;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;

import com.backtoback.gateway.dto.ErrorResponse;

import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 게이트웨이 표준 에러 응답 기록 유틸리티.
 */
public final class ErrorResponseWriter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ErrorResponseWriter() {}

    public static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }

        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        byte[] bytes;
        try {
            bytes = OBJECT_MAPPER.writeValueAsBytes(ErrorResponse.of(code, message));
        } catch (JacksonException exception) {
            String fallbackJson
                = String
                    .format(
                        "{\"success\":false,\"error\":{\"code\":\"%s\",\"message\":\"%s\",\"details\":[]}}",
                        code,
                        message
                    );
            bytes = fallbackJson.getBytes(StandardCharsets.UTF_8);
        }

        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }
}
