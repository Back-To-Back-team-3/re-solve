package com.backtoback.member.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backtoback.common.event.client.EventDestinationResolver;
import com.backtoback.common.event.dto.EventDestination;
import com.backtoback.common.event.dto.EventMetadata;
import com.backtoback.common.event.dto.OutboxEvent;
import com.backtoback.common.time.ClockConfiguration;

class MessagingConfigTest {

    private static final String TOPIC = "arn:aws:sns:ap-northeast-2:000000000000:resolve-local-member-events";
    private static final String QUEUE = "https://sqs.ap-northeast-2.amazonaws.com/000000000000/exam-submission-queue";

    @Test
    @DisplayName("진단 채점 요청(M6)은 SQS exam-submission-queue로, 다른 회원 이벤트는 SNS member-events로 보낸다")
    void routesDiagnosisSubmissionToQueue() {
        EventDestinationResolver resolver
            = new MessagingConfig().memberEventDestinationResolver(new MessagingProperties(true, TOPIC, QUEUE));

        assertThat(resolver.resolve(event("DiagnosisSubmissionRequested")))
            .isEqualTo(new EventDestination(EventDestination.Channel.SQS, QUEUE));
        assertThat(resolver.resolve(event("MemberUpdated")))
            .isEqualTo(new EventDestination(EventDestination.Channel.SNS, TOPIC));
    }

    @Test
    @DisplayName("발행을 켰는데 큐 주소가 없으면 기동 단계에서 실패한다")
    void requiresQueueUrl() {
        assertThatThrownBy(
            () -> new MessagingConfig().memberEventDestinationResolver(new MessagingProperties(true, TOPIC, ""))
        ).hasMessageContaining("EXAM_SUBMISSION_QUEUE_URL");
    }

    private static OutboxEvent event(String eventType) {
        return new OutboxEvent(
            1L,
            "MEMBER",
            new EventMetadata(
                UUID.randomUUID(),
                eventType,
                "v1",
                OffsetDateTime.now(ClockConfiguration.SERVICE_ZONE),
                "7",
                "test"
            ),
            "{}",
            0
        );
    }
}
