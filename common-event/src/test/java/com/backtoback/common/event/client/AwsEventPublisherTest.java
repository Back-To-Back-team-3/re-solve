package com.backtoback.common.event.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.common.event.dto.EventDestination;
import com.backtoback.common.event.dto.EventDestination.Channel;
import com.backtoback.common.event.dto.EventMetadata;
import com.backtoback.common.event.dto.OutboxEvent;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * 외부 AWS 호출 없이 SDK 요청의 원본 메시지·속성·타임아웃과 서비스별 채널 선택을 검증한다.
 * 실제 AWS 권한·네트워크·SNS 구독 구성은 검증 범위에 포함하지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class AwsEventPublisherTest {
    @Mock
    private SnsClient snsClient;

    @Mock
    private SqsClient sqsClient;

    @Mock
    private EventDestinationResolver destinationResolver;

    /**
     * SNS 구독 필터의 두 속성이 본문의 공통 헤더와 일치하고 호출 제한이 설정되는지 확인한다.
     */
    @Test
    @DisplayName("SNS는 저장한 메시지와 공통 속성을 그대로 보내고 전체 호출 제한을 2초로 지정한다")
    void publishesOriginalMessageToTopic() {
        // given
        var event = event();
        var destination = new EventDestination(Channel.SNS, "arn:aws:sns:ap-northeast-2:000000000000:test-events");
        given(destinationResolver.resolve(event)).willReturn(destination);
        var publisher = new AwsEventPublisher(snsClient, null, destinationResolver);

        // when
        publisher.publish(event);

        // then
        var request = ArgumentCaptor.forClass(PublishRequest.class);
        verify(snsClient).publish(request.capture());
        assertThat(request.getValue().topicArn()).isEqualTo(destination.address());
        assertThat(request.getValue().message()).isEqualTo(event.message());
        assertThat(request.getValue().messageAttributes().get("eventType").stringValue())
            .isEqualTo(event.metadata().eventType());
        assertThat(request.getValue().messageAttributes().get("schemaVersion").stringValue()).isEqualTo("v1");
        assertThat(request.getValue().messageAttributes().get("eventType").dataType()).isEqualTo("String");
        assertThat(request.getValue().messageAttributes().get("schemaVersion").dataType()).isEqualTo("String");
        assertThat(request.getValue().overrideConfiguration().orElseThrow().apiCallTimeout())
            .contains(Duration.ofSeconds(2));
        verifyNoInteractions(sqsClient);
    }

    /**
     * SQS 직접 전달에도 공통 속성을 유지하고 SNS 클라이언트를 생성하거나 사용하지 않는지 확인한다.
     */
    @Test
    @DisplayName("SQS는 지정한 큐로 원본 메시지와 공통 속성을 보내고 SNS 클라이언트를 요구하지 않는다")
    void sendsOriginalMessageToQueue() {
        // given
        var event = event();
        var destination
            = new EventDestination(Channel.SQS, "https://sqs.ap-northeast-2.amazonaws.com/000000000000/test");
        given(destinationResolver.resolve(event)).willReturn(destination);
        var publisher = new AwsEventPublisher(null, sqsClient, destinationResolver);

        // when
        publisher.publish(event);

        // then
        var request = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqsClient).sendMessage(request.capture());
        assertThat(request.getValue().queueUrl()).isEqualTo(destination.address());
        assertThat(request.getValue().messageBody()).isEqualTo(event.message());
        assertThat(request.getValue().messageAttributes().get("eventType").dataType()).isEqualTo("String");
        assertThat(request.getValue().messageAttributes().get("schemaVersion").dataType()).isEqualTo("String");
        assertThat(request.getValue().messageAttributes().get("eventType").stringValue())
            .isEqualTo(event.metadata().eventType());
        assertThat(request.getValue().messageAttributes().get("schemaVersion").stringValue()).isEqualTo("v1");
        assertThat(request.getValue().overrideConfiguration().orElseThrow().apiCallTimeout())
            .contains(Duration.ofSeconds(2));
        verifyNoInteractions(snsClient);
    }

    /**
     * 발행 성공으로 오인하지 않도록 SDK 타임아웃·통신 오류가 Relay까지 전파되는지 확인한다.
     */
    @Test
    @DisplayName("AWS 발행 오류는 삼키지 않고 Relay의 재시도 처리로 전달한다")
    void propagatesSdkPublicationFailure() {
        // given
        var event = event();
        given(destinationResolver.resolve(event))
            .willReturn(new EventDestination(Channel.SNS, "arn:aws:sns:ap-northeast-2:000000000000:test-events"));
        var failure = SdkClientException.create("simulated network failure");
        willThrow(failure).given(snsClient).publish(any(PublishRequest.class));
        var publisher = new AwsEventPublisher(snsClient, null, destinationResolver);

        // when & then
        assertThatThrownBy(() -> publisher.publish(event)).isSameAs(failure);
    }

    /**
     * 다른 채널만 구성한 서비스에서 요청한 채널이 누락되면 발행 성공으로 처리하지 않는다.
     */
    @Test
    @DisplayName("선택한 채널의 클라이언트가 없으면 원격 호출 전에 실패한다")
    void rejectsMissingSelectedClient() {
        // given
        var event = event();
        given(destinationResolver.resolve(event))
            .willReturn(
                new EventDestination(Channel.SQS, "https://sqs.ap-northeast-2.amazonaws.com/000000000000/test")
            );
        var publisher = new AwsEventPublisher(snsClient, null, destinationResolver);

        // when & then
        assertThatThrownBy(() -> publisher.publish(event)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(snsClient, sqsClient);
    }

    /**
     * 토픽·큐 생성은 서비스 소유로 두되 공통 전달 계약에서 금지한 FIFO 리소스는 거절한다.
     */
    @Test
    @DisplayName("FIFO 주소와 빈 리소스는 공통 Standard 전달 대상으로 허용하지 않는다")
    void rejectsFifoAndEmptyDestinations() {
        // when & then
        assertThatIllegalArgumentException().isThrownBy(() -> new EventDestination(Channel.SNS, "events.fifo"));
        assertThatIllegalArgumentException().isThrownBy(() -> new EventDestination(Channel.SQS, "queue.fifo"));
        assertThatIllegalArgumentException().isThrownBy(() -> new EventDestination(Channel.SQS, ""));
    }

    /**
     * 전송 과정에서 내용이 바뀌지 않는지 비교할 고정 저장 이벤트를 만든다.
     *
     * @return DB 저장을 가정한 메시지와 메타데이터
     */
    private OutboxEvent event() {
        var metadata
            = new EventMetadata(
                UUID.fromString("d65b1858-9741-465f-90f7-5af96ad7bd60"),
                "ExamSubmissionRequested",
                "v1",
                OffsetDateTime.parse("2026-10-29T21:00:00.123456+09:00"),
                "55",
                "request-test"
            );
        return new OutboxEvent(1, "EXAM_SUBMISSION", metadata, """
            {"eventId":"d65b1858-9741-465f-90f7-5af96ad7bd60","eventType":"ExamSubmissionRequested",
            "schemaVersion":"v1","occurredAt":"2026-10-29T21:00:00.123456+09:00",
            "aggregateId":"55","correlationId":"request-test","examSubmissionId":"55"}
            """, 0);
    }
}
