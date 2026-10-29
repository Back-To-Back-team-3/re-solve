package com.backtoback.common.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Raw 이벤트와 SNS 봉투가 같은 수신 모델로 해석되는지 공통 헤더의 형식 경계와 함께 검증한다.
 * 업무 필드 검증이나 SNS 토픽 권한을 JSON 해석과 혼동하지 않는다.
 */
class EventMessageDecoderTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final EventMessageDecoder decoder = new EventMessageDecoder(mapper);

    /**
     * 새로운 선택 필드를 허용하면서 문자열 ID·정수 순서 값·빈 배열을 그대로 유지하는지 확인한다.
     */
    @Test
    @DisplayName("Raw 이벤트의 공통 헤더를 분리하고 모르는 선택 필드와 업무 타입을 보존한다")
    void decodesRawEventAndPreservesAdditionalPayloadFields() {
        // given
        var message = message();

        // when
        var event = decoder.decode(message);

        // then
        assertThat(event.metadata().eventId()).isEqualTo(UUID.fromString("d65b1858-9741-465f-90f7-5af96ad7bd60"));
        assertThat(event.metadata().eventType()).isEqualTo("SubmissionJudged");
        assertThat(event.metadata().schemaVersion()).isEqualTo("v1");
        assertThat(event.metadata().aggregateId()).isEqualTo("55");
        assertThat(event.metadata().correlationId()).isEqualTo("request-test");
        assertThat(event.metadata().occurredAt()).isEqualTo(OffsetDateTime.parse("2026-10-29T21:00:00.123456+09:00"));
        assertThat(event.payload()).isEqualTo(mapper.readTree("""
            {"submissionId":"9007199254740993","judgeAttempt":2,"tags":[],"futureField":"kept"}
            """));
        assertThat(event.payload().has("eventId")).isFalse();
    }

    /**
     * SNS 봉투의 Message만 한 번 해제하고 Raw 수신과 같은 메타데이터·업무 필드를 전달하는지 확인한다.
     */
    @Test
    @DisplayName("SNS Notification 봉투와 Raw 이벤트는 같은 공통 수신 모델로 변환한다")
    void unwrapsSnsNotification() {
        // given
        var raw = message();
        var envelope = mapper.writeValueAsString(Map.of("Type", "Notification", "Message", raw));

        // when
        var received = decoder.decode(envelope);

        // then
        assertThat(received).isEqualTo(decoder.decode(raw));
    }

    /**
     * 필수 헤더별 누락·숫자·공백을 검사해 JSON 강제 문자열 변환으로 통과하는 경우를 방지한다.
     */
    @Test
    @DisplayName("필수 공통 헤더가 없거나 문자열이 아니거나 비어 있으면 수신을 거절한다")
    void rejectsMissingAndNonStringHeaders() {
        // given
        var headers = List.of("eventId", "eventType", "schemaVersion", "occurredAt", "aggregateId", "correlationId");

        // when & then
        for (var header : headers) {
            var missing = (ObjectNode)mapper.readTree(message());
            missing.remove(header);
            assertThatIllegalArgumentException().isThrownBy(() -> decoder.decode(mapper.writeValueAsString(missing)));
            var invalid = (ObjectNode)mapper.readTree(message());
            invalid.put(header, 55);
            assertThatIllegalArgumentException().isThrownBy(() -> decoder.decode(mapper.writeValueAsString(invalid)));
            invalid.put(header, " ");
            assertThatIllegalArgumentException().isThrownBy(() -> decoder.decode(mapper.writeValueAsString(invalid)));
        }
    }

    /**
     * 수신 시간의 임의 정규화나 UUID 축약 해석으로 계약 위반을 숨기지 않는지 확인한다.
     */
    @Test
    @DisplayName("축약 UUID와 서울 시간 6자리 형식이 아닌 발생 시각을 거절한다")
    void rejectsInvalidIdentifierAndTimestampFormats() {
        // given
        var invalidTime = message().replace("2026-10-29T21:00:00.123456+09:00", "2026-10-29T12:00:00Z");
        var invalidId = message().replace("d65b1858-9741-465f-90f7-5af96ad7bd60", "1-1-4001-8001-1");

        // when & then
        assertThatIllegalArgumentException().isThrownBy(() -> decoder.decode(invalidTime));
        assertThatIllegalArgumentException().isThrownBy(() -> decoder.decode(invalidId));
    }

    /**
     * 숫자 형식만 맞는 잘못된 날짜도 문서화된 메타데이터 검증 예외로 전달하는지 확인한다.
     */
    @Test
    @DisplayName("달력상 불가능한 발생 시각은 파싱 원인을 보존한 IllegalArgumentException으로 거절한다")
    void rejectsInvalidCalendarTimestamp() {
        // given
        var invalidDate = message().replace("2026-10-29T21:00:00.123456+09:00", "2026-02-30T21:00:00.123456+09:00");

        // when & then
        assertThatIllegalArgumentException()
            .isThrownBy(() -> decoder.decode(invalidDate))
            .withMessage("occurredAt must be a valid calendar date-time")
            .withCauseInstanceOf(DateTimeParseException.class);
    }

    /**
     * 기본 예시와 다른 애그리게이트 ID도 그대로 전달해 고정값으로 바뀌는 회귀를 방지한다.
     */
    @Test
    @DisplayName("기본 예시와 다른 aggregateId도 문자열 값 그대로 전달한다")
    void preservesDifferentAggregateId() {
        // given
        var body = (ObjectNode)mapper.readTree(message());
        body.put("aggregateId", "9007199254740993");

        // when
        var event = decoder.decode(mapper.writeValueAsString(body));

        // then
        assertThat(event.metadata().aggregateId()).isEqualTo("9007199254740993");
    }

    /**
     * 구독 확인 메시지·문자열이 아닌 SNS Message·배열을 업무 이벤트로 실행하지 않는지 확인한다.
     */
    @Test
    @DisplayName("SNS 구독 확인과 잘못된 봉투 또는 JSON 객체가 아닌 본문을 거절한다")
    void rejectsUnsupportedEnvelopesAndNonObjectMessages() {
        // given
        var confirmation = mapper.writeValueAsString(Map.of("Type", "SubscriptionConfirmation", "Message", message()));
        var invalidEnvelope = mapper.writeValueAsString(Map.of("Type", "Notification", "Message", Map.of()));

        // when & then
        assertThatIllegalArgumentException().isThrownBy(() -> decoder.decode(confirmation));
        assertThatIllegalArgumentException().isThrownBy(() -> decoder.decode(invalidEnvelope));
        assertThatIllegalArgumentException().isThrownBy(() -> decoder.decode("[]"));
        assertThatIllegalArgumentException().isThrownBy(() -> decoder.decode("null"));
        assertThatIllegalArgumentException().isThrownBy(() -> decoder.decode(" "));
        assertThatThrownBy(() -> decoder.decode("{")).isInstanceOf(JacksonException.class);
    }

    /**
     * DB 전용 필드가 메시지로 전달되지 않는 발행·수신 공통 경계를 확인한다.
     */
    @Test
    @DisplayName("Outbox 전용 aggregate_type이 메시지에 섞이면 수신을 거절한다")
    void rejectsOutboxOnlyField() {
        // given
        var body = (ObjectNode)mapper.readTree(message());
        body.put("aggregate_type", "SUBMISSION");

        // when & then
        assertThatIllegalArgumentException().isThrownBy(() -> decoder.decode(mapper.writeValueAsString(body)));
    }

    /**
     * 계약의 필수 헤더와 미래 선택 필드를 함께 가진 고정 수신 메시지를 만든다.
     *
     * @return 정상 메타데이터와 서비스 업무 필드를 가진 Raw JSON
     */
    private String message() {
        return """
            {"eventId":"d65b1858-9741-465f-90f7-5af96ad7bd60","eventType":"SubmissionJudged",
            "schemaVersion":"v1","occurredAt":"2026-10-29T21:00:00.123456+09:00",
            "aggregateId":"55","correlationId":"request-test",
            "submissionId":"9007199254740993","judgeAttempt":2,"tags":[],"futureField":"kept"}
            """;
    }
}
