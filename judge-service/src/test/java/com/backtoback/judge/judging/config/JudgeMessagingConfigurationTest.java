package com.backtoback.judge.judging.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.backtoback.common.event.dto.EventDestination;
import com.backtoback.common.event.dto.EventMetadata;
import com.backtoback.common.event.dto.OutboxEvent;

import jakarta.validation.Validation;

/** 결과 이벤트 목적지와 활성화 시 필요한 리소스 제약을 검증한다. */
class JudgeMessagingConfigurationTest {
    private static final String TOPIC = "arn:aws:sns:ap-northeast-2:000000000000:judge-events";

    /**
     * 확정된 결과 이벤트를 같은 SNS 토픽으로 연결한다.
     *
     * @param type 이벤트명
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "ProblemValidationCompleted",
            "SubmissionJudged",
            "ExamSubmissionJudged",
            "ContestSubmissionJudged",
            "SubmissionFailed"
        }
    )
    @DisplayName("Judge 결과 이벤트는 설정한 Standard SNS 토픽으로 발행한다")
    void resolvesResultEvents(String type) {
        var resolver
            = new JudgeMessagingConfiguration()
                .judgeEventDestinationResolver(
                    new JudgeMessagingProperties(TOPIC, "https://sqs.example/000000000000/problem-validation-queue")
                );
        assertThat(resolver.resolve(event(type, "v1")))
            .isEqualTo(new EventDestination(EventDestination.Channel.SNS, TOPIC));
        assertThatThrownBy(() -> resolver.resolve(event(type, "v2"))).isInstanceOf(IllegalArgumentException.class);
    }

    /** 요청 또는 미확정 이벤트를 결과로 오발행하지 않는지 검증한다. */
    @Test
    @DisplayName("요청 이벤트와 미확정 진단 결과는 결과 토픽으로 보내지 않는다")
    void rejectsUnregisteredEvents() {
        var resolver
            = new JudgeMessagingConfiguration().judgeEventDestinationResolver(new JudgeMessagingProperties(TOPIC, ""));
        assertThatThrownBy(() -> resolver.resolve(event("ProblemValidationRequested", "v1")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve(event("DiagnosisSubmissionJudged", "v1")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 활성화할 큐 주소가 Standard URL인지 검증한다.
     *
     * @param queue 잘못된 주소
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "",
            "problem-validation-queue",
            "https://sqs.example/queue.fifo",
            "https://sqs.example/a b"
        }
    )
    @DisplayName("빈 주소·큐 이름·FIFO 큐·공백 주소를 거절한다")
    void rejectsInvalidQueueAddress(String queue) {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(new JudgeMessagingProperties(TOPIC, queue))).isNotEmpty();
        }
    }

    /**
     * 목적지 판별용 이벤트를 생성한다.
     *
     * @param type 이벤트명
     * @param version 형식 버전
     * @return Outbox 이벤트
     */
    private OutboxEvent event(String type, String version) {
        return new OutboxEvent(
            1,
            "VALIDATION",
            new EventMetadata(
                UUID.randomUUID(),
                type,
                version,
                OffsetDateTime.parse("2026-10-31T09:00:00+09:00"),
                "2001",
                "req-test"
            ),
            "{}",
            0
        );
    }
}
