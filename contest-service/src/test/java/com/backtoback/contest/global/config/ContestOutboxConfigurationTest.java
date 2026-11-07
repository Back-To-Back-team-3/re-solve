package com.backtoback.contest.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.common.event.client.AwsEventPublisher;
import com.backtoback.common.event.dto.EventMetadata;
import com.backtoback.common.event.dto.OutboxEvent;

import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

@ExtendWith(MockitoExtension.class)
class ContestOutboxConfigurationTest {
    private static final String TOPIC_ARN = "arn:aws:sns:ap-northeast-2:000000000000:contest-events";

    @Mock
    private SnsClient sns;

    @ParameterizedTest
    @ValueSource(
        strings = {
            "ExamUpdated",
            "ExamParticipantRegistered",
            "ExamParticipantCanceled",
            "ExamStarted",
            "ExamClosed",
            "ExamCanceled"
        }
    )
    @DisplayName("시험 설정 변경과 참가 이벤트를 지정한 SNS 토픽에 원본 그대로 발행한다")
    void publishesContestEvent(String eventType) {
        var resolver
            = new ContestOutboxConfiguration().contestEventDestinationResolver(new ContestOutboxProperties(TOPIC_ARN));
        var event = event(eventType);

        new AwsEventPublisher(sns, null, resolver).publish(event);

        var request = ArgumentCaptor.forClass(PublishRequest.class);
        verify(sns).publish(request.capture());
        assertThat(request.getValue().topicArn()).isEqualTo(TOPIC_ARN);
        assertThat(request.getValue().message()).isEqualTo(event.message());
        assertThat(request.getValue().messageAttributes().get("eventType").stringValue()).isEqualTo(eventType);
    }

    @Test
    @DisplayName("발행 경로가 연결되지 않은 이벤트를 시험 SNS 토픽으로 보내지 않는다")
    void rejectsUnsupportedEvent() {
        var resolver
            = new ContestOutboxConfiguration().contestEventDestinationResolver(new ContestOutboxProperties(TOPIC_ARN));

        assertThatThrownBy(() -> resolver.resolve(event("ExamSubmissionRequested")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private OutboxEvent event(String eventType) {
        var metadata
            = new EventMetadata(
                UUID.randomUUID(),
                eventType,
                "v1",
                OffsetDateTime.parse("2026-11-07T00:00:00+09:00"),
                "7",
                "test"
            );
        return new OutboxEvent(1L, "EXAM", metadata, "{}", 0);
    }
}
