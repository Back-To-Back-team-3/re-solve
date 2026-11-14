package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backtoback.common.event.dto.EventMetadata;
import com.backtoback.common.event.service.EventJsonCodec;
import com.backtoback.contest.exam.service.ExamSubmissionMessageSize.Payload;
import com.backtoback.contest.global.error.ContestException;

import tools.jackson.databind.ObjectMapper;

/** 공통 직렬화 결과와 코드 원문의 바이트 제한을 각각 확인한다. */
class ExamSubmissionMessageSizeTest {
    private final EventJsonCodec codec = new EventJsonCodec(new ObjectMapper());
    private final ExamSubmissionMessageSize size = new ExamSubmissionMessageSize(codec);

    /** ASCII 원문은 64KiB까지 허용하고 한 바이트 초과를 거절한다. */
    @Test
    @DisplayName("원문 64KiB는 허용하고 초과하면 거절한다")
    void codeLimit() {
        // when & then
        assertThatCode(() -> size.validate("a".repeat(65536))).doesNotThrowAnyException();
        assertThatThrownBy(() -> size.validate("a".repeat(65537))).isInstanceOf(ContestException.class);
    }

    @Test
    @DisplayName("UTF-8 다중 바이트 원문은 문자 수 대신 바이트 경계로 제한한다")
    void multibyteCodeLimit() {
        String boundary = "가".repeat(21845) + "a";
        assertThatCode(() -> size.validate(boundary)).doesNotThrowAnyException();
        assertThatThrownBy(() -> size.validate(boundary + "a")).isInstanceOf(ContestException.class);
    }

    /** 제어 문자가 JSON에서 확장된 실제 메시지의 256KiB 경계를 확인한다. */
    @Test
    @DisplayName("최대 메타데이터에서 직렬화된 이벤트 256KiB까지 허용한다")
    void serializedLimit() {
        // given
        String prefix = String.valueOf((char)1).repeat(43500);
        int remaining = 262144 - bytes(prefix);
        String boundary = prefix + "a".repeat(remaining);
        // when & then
        assertThatCode(() -> size.validate(boundary)).doesNotThrowAnyException();
        assertThatThrownBy(() -> size.validate(boundary + "a")).isInstanceOf(ContestException.class);
    }

    /** 모든 가변 필드를 최대 길이로 채운 평평한 이벤트의 바이트를 센다. */
    private int bytes(String source) {
        String id = "9223372036854775807";
        String time = "9999-12-31T23:59:59.999999+09:00";
        var metadata
            = new EventMetadata(
                UUID.fromString("ffffffff-ffff-4fff-bfff-ffffffffffff"),
                "ExamSubmissionRequested",
                "v1",
                OffsetDateTime.parse(time),
                id,
                String.valueOf((char)1).repeat(64)
            );
        var payload = new Payload(id, id, id, id, id, id, "python3", source, id, "MANUAL", time);
        return codec.serialize(metadata, payload).getBytes(StandardCharsets.UTF_8).length;
    }
}
