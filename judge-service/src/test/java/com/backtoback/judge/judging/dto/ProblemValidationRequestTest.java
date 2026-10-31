package com.backtoback.judge.judging.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.backtoback.common.event.dto.EventMetadata;
import com.backtoback.common.event.dto.ReceivedEvent;

import tools.jackson.databind.json.JsonMapper;

/** 검증 이벤트의 불변 식별자와 선택 필드 호환성을 검증한다. */
class ProblemValidationRequestTest {
    /** 필수 필드는 유지하면서 미래 선택 필드를 허용하는지 검증한다. */
    @Test
    @DisplayName("알려지지 않은 선택 필드가 있어도 요청을 해석한다")
    void acceptsAdditionalOptionalFields() {
        var event = event("\"2001\"", "\"1001\"", "a".repeat(64));
        assertThat(ProblemValidationRequest.from(event))
            .isEqualTo(new ProblemValidationRequest(2001, 1001, "java", "a".repeat(64)));
    }

    /**
     * JSON 숫자·null과 비정규 문자열 ID를 거절하는지 검증한다.
     *
     * @param identifier 테스트 JSON 값
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "null",
            "1001",
            "\"0\"",
            "\"-1\"",
            "\"01\"",
            "\" 1\"",
            "\"9223372036854775808\""
        }
    )
    @DisplayName("문자열 양의 BIGINT가 아닌 회차 ID를 거절한다")
    void rejectsInvalidRevision(String identifier) {
        assertThatThrownBy(() -> ProblemValidationRequest.from(event("\"2001\"", identifier, "a".repeat(64))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** aggregateId가 다른 검증 요청을 저장하지 않는지 검증한다. */
    @Test
    @DisplayName("검증 ID가 aggregateId와 다르면 거절한다")
    void rejectsMismatchedAggregate() {
        assertThatThrownBy(() -> ProblemValidationRequest.from(event("\"2002\"", "\"1001\"", "a".repeat(64))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * SHA-256 형식과 원문 보존을 검증한다.
     *
     * @param hash 유효하지 않은 해시
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "",
            "abc",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        }
    )
    @DisplayName("64자 소문자 16진수가 아닌 요청 해시를 거절한다")
    void rejectsInvalidHash(String hash) {
        assertThatThrownBy(() -> ProblemValidationRequest.from(event("\"2001\"", "\"1001\"", hash)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 검증할 업무 필드를 공통 메타데이터와 결합한다.
     *
     * @param validationId 검증 ID JSON 값
     * @param revisionId 회차 ID JSON 값
     * @param hash 해시 원문
     * @return 검사 대상 이벤트
     */
    private ReceivedEvent event(String validationId, String revisionId, String hash) {
        var payload = JsonMapper.builder().build().readTree("""
            {"validationId":%s,"problemRevisionId":%s,"language":"java","requestHash":"%s","optional":"ignored"}
            """.formatted(validationId, revisionId, hash));
        return new ReceivedEvent(
            new EventMetadata(
                UUID.randomUUID(),
                "ProblemValidationRequested",
                "v1",
                OffsetDateTime.parse("2026-10-31T09:00:00+09:00"),
                "2001",
                "req-test"
            ),
            payload
        );
    }
}
