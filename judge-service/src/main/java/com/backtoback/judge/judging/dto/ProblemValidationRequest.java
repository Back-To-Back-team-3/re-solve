package com.backtoback.judge.judging.dto;

import com.backtoback.common.event.dto.ReceivedEvent;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.domain.JudgeLanguage;

import tools.jackson.databind.JsonNode;

/**
 * 검증 이벤트의 불변 식별 정보를 보관한다. (이벤트 계약 §4.2 P2)
 *
 * @param validationId Problem의 검증 ID
 * @param problemRevisionId 고정 회차 ID
 * @param language 검증 언어 코드
 * @param requestHash 원래 요청의 SHA-256
 */
public record ProblemValidationRequest(long validationId, long problemRevisionId, String language, String requestHash) {
    /**
     * 저장 전에 식별자·언어·해시 형식을 검사한다.
     *
     * @throws IllegalArgumentException 식별자 또는 해시가 유효하지 않은 경우
     * @throws JudgeException {@code UNSUPPORTED_LANGUAGE}: 지원하지 않는 언어인 경우
     */
    public ProblemValidationRequest {
        if (validationId <= 0 || problemRevisionId <= 0 || requestHash == null
            || !requestHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("검증 식별자 또는 요청 해시가 유효하지 않습니다.");
        }
        JudgeLanguage.fromCode(language);
    }

    /**
     * 필수 필드를 문자열로 검사하고 aggregateId와 검증 ID를 대조한다.
     *
     * @param event 공통 형식 검사를 마친 수신 이벤트
     * @return 검증 작업의 불변 식별 정보
     * @throws IllegalArgumentException 필드 타입·값 또는 aggregateId가 일치하지 않는 경우
     * @throws JudgeException {@code UNSUPPORTED_LANGUAGE}: 지원하지 않는 언어인 경우
     */
    public static ProblemValidationRequest from(ReceivedEvent event) {
        var payload = event.payload();
        var validationId = text(payload, "validationId");
        if (!validationId.equals(event.metadata().aggregateId())) {
            throw new IllegalArgumentException("검증 ID와 aggregateId가 일치하지 않습니다.");
        }
        return new ProblemValidationRequest(
            identifier(validationId),
            identifier(text(payload, "problemRevisionId")),
            text(payload, "language"),
            text(payload, "requestHash")
        );
    }

    /**
     * 메시지 필수 문자열을 숫자·null로부터 구분한다.
     *
     * @param payload 업무 필드 JSON
     * @param name 필드 이름
     * @return 필드 원문
     * @throws IllegalArgumentException 문자열이 아닌 경우
     */
    private static String text(JsonNode payload, String name) {
        var value = payload.get(name);
        if (value == null || !value.isString()) {
            throw new IllegalArgumentException("검증 이벤트 필수 문자열이 없습니다.");
        }
        return value.asString();
    }

    /**
     * 양의 BIGINT를 정규 10진 문자열에서 변환한다.
     *
     * @param value 숫자 문자열
     * @return 저장할 ID
     * @throws IllegalArgumentException 형식 또는 BIGINT 범위를 벗어난 경우
     */
    private static long identifier(String value) {
        if (!value.matches("[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException("검증 식별자 형식이 유효하지 않습니다.");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("검증 식별자 범위가 유효하지 않습니다.");
        }
    }
}
