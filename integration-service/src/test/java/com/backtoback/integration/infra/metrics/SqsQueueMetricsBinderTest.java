package com.backtoback.integration.infra.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

@ExtendWith(MockitoExtension.class)
class SqsQueueMetricsBinderTest {

    @Mock
    private SqsAsyncClient sqsAsyncClient;

    @Test
    @DisplayName("SqsQueueMetricsBinder는 SQS 큐 메트릭 게이지를 등록하고 새로고침 시 값을 갱신한다")
    void shouldRegisterAndRefreshSqsMetrics() {
        // given
        MeterRegistry registry = new SimpleMeterRegistry();
        String queueName = "test-queue";
        String queueUrl = "https://sqs.ap-northeast-2.amazonaws.com/123456789012/test-queue";

        given(sqsAsyncClient.getQueueUrl(any(GetQueueUrlRequest.class)))
            .willReturn(CompletableFuture.completedFuture(GetQueueUrlResponse.builder().queueUrl(queueUrl).build()));

        Map<QueueAttributeName, String> attributes
            = Map
                .of(
                    QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                    "15",
                    QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE,
                    "3",
                    QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED,
                    "1"
                );
        given(sqsAsyncClient.getQueueAttributes(any(GetQueueAttributesRequest.class)))
            .willReturn(
                CompletableFuture.completedFuture(GetQueueAttributesResponse.builder().attributes(attributes).build())
            );

        SqsQueueMetricsBinder binder = new SqsQueueMetricsBinder(sqsAsyncClient, queueName);
        binder.bindTo(registry);

        // when
        binder.refreshQueueMetrics();

        // then
        Double visible = registry.get("aws.sqs.messages.visible").tag("queue", queueName).gauge().value();
        Double notVisible = registry.get("aws.sqs.messages.not_visible").tag("queue", queueName).gauge().value();
        Double delayed = registry.get("aws.sqs.messages.delayed").tag("queue", queueName).gauge().value();

        assertThat(visible).isEqualTo(15.0);
        assertThat(notVisible).isEqualTo(3.0);
        assertThat(delayed).isEqualTo(1.0);
    }

    @Test
    @DisplayName("sqsAsyncClient가 null인 경우에도 바인딩과 게이지는 0으로 안전하게 동작한다")
    void shouldHandleNullClientGracefully() {
        MeterRegistry registry = new SimpleMeterRegistry();
        SqsQueueMetricsBinder binder = new SqsQueueMetricsBinder(null, "test-queue");
        binder.bindTo(registry);

        binder.refreshQueueMetrics();

        Double visible = registry.get("aws.sqs.messages.visible").gauge().value();
        assertThat(visible).isEqualTo(0.0);
    }
}
