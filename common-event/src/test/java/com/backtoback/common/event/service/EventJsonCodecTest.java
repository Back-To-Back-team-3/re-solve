package com.backtoback.common.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backtoback.common.event.dto.EventMetadata;

import tools.jackson.databind.ObjectMapper;

/**
 * 업무 record를 평평한 이벤트 JSON으로 변환할 때 공통 헤더와 업무 필드의 경계를 검증한다.
 * 시간대·정밀도·문자열 ID를 이벤트 계약서 v1.0 §1.1·§1.2와 대조한다.
 */
class EventJsonCodecTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EventJsonCodec codec = new EventJsonCodec(objectMapper);

    /**
     * 숫자 문자열 ID와 정수 순서 값을 유지하고 발생 시각만 서울 시간 6자리로 통일하는지 확인한다.
     */
    @Test
    @DisplayName("공통 헤더와 업무 record는 중첩 없이 합치고 서울 시간 마이크로초로 발행한다")
    void serializesFlatMessageWithSeoulMicroseconds() {
        // given
        var metadata = metadata();
        var payload = new ResultPayload("9007199254740993", 2, List.of());

        // when
        var message = objectMapper.readTree(codec.serialize(metadata, payload));

        // then
        assertThat(message).isEqualTo(objectMapper.readTree("""
            {
                "eventId":"d65b1858-9741-465f-90f7-5af96ad7bd60",
                "eventType":"SubmissionJudged",
                "schemaVersion":"v1",
                "occurredAt":"2026-10-29T21:00:00.123456+09:00",
                "aggregateId":"55",
                "correlationId":"request-test",
                "submissionId":"9007199254740993",
                "judgeAttempt":2,
                "tags":[]
            }
            """));
    }

    /**
     * 서비스가 보관하는 JSON 객체가 직렬화 과정에서 헤더 추가로 변경되지 않는지 확인한다.
     */
    @Test
    @DisplayName("직렬화는 호출자가 전달한 JSON 객체를 변경하지 않는다")
    void preservesOriginalPayload() {
        // given
        var payload = objectMapper.createObjectNode().put("submissionId", "55");
        var before = payload.deepCopy();

        // when
        codec.serialize(metadata(), payload);

        // then
        assertThat(payload).isEqualTo(before);
    }

    /**
     * 헤더 위조와 DB 전용 애그리게이트 타입의 메시지 유출을 같은 경계에서 거절하는지 확인한다.
     */
    @Test
    @DisplayName("업무 필드가 공통 헤더나 DB 전용 aggregate_type을 포함하면 거절한다")
    void rejectsReservedPayloadFields() {
        // given
        var reservedFields
            = List
                .of(
                    "eventId",
                    "eventType",
                    "schemaVersion",
                    "occurredAt",
                    "aggregateId",
                    "correlationId",
                    "aggregate_type"
                );

        // when & then
        for (var field : reservedFields) {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> codec.serialize(metadata(), Map.of(field, "override")))
                .withMessageContaining(field);
        }
    }

    /**
     * 배열·문자열·null은 공통 헤더와 합칠 수 있는 업무 객체가 아니므로 발행 전에 차단한다.
     */
    @Test
    @DisplayName("업무 필드가 JSON 객체가 아니면 직렬화를 거절한다")
    void rejectsNonObjectPayload() {
        // given
        var metadata = metadata();

        // when & then
        assertThatIllegalArgumentException().isThrownBy(() -> codec.serialize(metadata, List.of("55")));
        assertThatIllegalArgumentException().isThrownBy(() -> codec.serialize(metadata, "55"));
        assertThatIllegalArgumentException().isThrownBy(() -> codec.serialize(metadata, null));
    }

    /**
     * UUID 형식이더라도 v4가 아니면 재발행 식별자로 허용하지 않고 DB 공통 컬럼 길이도 확인한다.
     */
    @Test
    @DisplayName("UUID v4가 아닌 ID와 잘못된 헤더 값은 메타데이터 생성 시 거절한다")
    void rejectsInvalidMetadata() {
        // given
        var valid = metadata();

        // when & then
        assertThatIllegalArgumentException()
            .isThrownBy(
                () -> new EventMetadata(
                    new UUID(0, 0),
                    valid.eventType(),
                    "v1",
                    valid.occurredAt(),
                    "55",
                    "request-test"
                )
            );
        assertThatIllegalArgumentException()
            .isThrownBy(
                () -> new EventMetadata(
                    valid.eventId(),
                    valid.eventType(),
                    "v1",
                    valid.occurredAt(),
                    "exam-55",
                    "request-test"
                )
            );
        assertThatIllegalArgumentException()
            .isThrownBy(
                () -> new EventMetadata(
                    valid.eventId(),
                    valid.eventType(),
                    "v1",
                    valid.occurredAt(),
                    "55",
                    "x".repeat(65)
                )
            );
        assertThatIllegalArgumentException()
            .isThrownBy(
                () -> new EventMetadata(
                    valid.eventId(),
                    valid.eventType(),
                    "",
                    valid.occurredAt(),
                    "55",
                    "request-test"
                )
            );
    }

    /**
     * 나노초와 UTC 오프셋을 포함해 시간대 변환·정밀도 절삭을 확인할 헤더를 만든다.
     *
     * @return JSON 스냅샷에서 재사용할 고정 메타데이터
     */
    private EventMetadata metadata() {
        return new EventMetadata(
            UUID.fromString("d65b1858-9741-465f-90f7-5af96ad7bd60"),
            "SubmissionJudged",
            "v1",
            OffsetDateTime.parse("2026-10-29T12:00:00.123456789Z"),
            "55",
            "request-test"
        );
    }

    /**
     * 문자열 ID·정수 순서 값·빈 목록을 가진 테스트용 서비스 payload다.
     *
     * @param submissionId JavaScript 안전 정수 범위를 넘는 문자열 ID
     * @param judgeAttempt 공통 헤더와 분리되는 업무 순서 값
     * @param tags 비어 있어도 배열로 발행할 태그
     */
    record ResultPayload(String submissionId, int judgeAttempt, List<String> tags) {
    }
}
