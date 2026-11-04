package com.backtoback.integration.infra.metrics;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * SQS 대기 메시지 수(ApproximateNumberOfMessages)를 주기적으로 갱신하여 Prometheus 게이지로 노출한다.
 */
@Slf4j
@Component
@ConditionalOnClass(SqsAsyncClient.class)
public class SqsQueueMetricsBinder implements MeterBinder {

    private final SqsAsyncClient sqsAsyncClient;
    private final String queueName;
    private final AtomicLong visibleMessages = new AtomicLong(0);
    private final AtomicLong notVisibleMessages = new AtomicLong(0);
    private final AtomicLong delayedMessages = new AtomicLong(0);
    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong(Instant.now().getEpochSecond());
    private volatile String cachedQueueUrl;

    public SqsQueueMetricsBinder(
        @Autowired(required = false) SqsAsyncClient sqsAsyncClient,
        @Value("${integration.sqs.queue-name:integration-queue}") String queueName
    ) {
        this.sqsAsyncClient = sqsAsyncClient;
        this.queueName = queueName;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge
            .builder("aws.sqs.messages.visible", visibleMessages, AtomicLong::get)
            .tag("queue", queueName)
            .description("Approximate number of messages available for retrieval from the SQS queue")
            .register(registry);

        Gauge
            .builder("aws.sqs.messages.not_visible", notVisibleMessages, AtomicLong::get)
            .tag("queue", queueName)
            .description("Approximate number of messages in flight (not visible) in the SQS queue")
            .register(registry);

        Gauge
            .builder("aws.sqs.messages.delayed", delayedMessages, AtomicLong::get)
            .tag("queue", queueName)
            .description("Approximate number of messages delayed and not available for immediate retrieval")
            .register(registry);

        if (sqsAsyncClient != null) {
            Gauge
                .builder("aws.sqs.metrics.last.success.timestamp.seconds", lastSuccessEpochSeconds, AtomicLong::get)
                .tag("queue", queueName)
                .description("Epoch seconds of the last successful SQS queue metrics refresh")
                .register(registry);
        }
    }

    /**
     * SQS 대기/처리중/지연 메시지 수를 조회하여 원자적 게이지 값을 갱신한다.
     */
    @Scheduled(
        fixedDelayString = "${integration.metrics.sqs.refresh-interval-ms:15000}",
        initialDelay = 5000
    )
    public void refreshQueueMetrics() {
        if (sqsAsyncClient == null) {
            return;
        }
        try {
            String queueUrl = resolveQueueUrl();
            if (queueUrl == null) {
                return;
            }
            var request
                = GetQueueAttributesRequest
                    .builder()
                    .queueUrl(queueUrl)
                    .attributeNames(
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED
                    )
                    .build();

            var response = sqsAsyncClient.getQueueAttributes(request).get(3, TimeUnit.SECONDS);
            var attributes = response.attributes();

            String visibleStr = attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES);
            if (visibleStr != null) {
                visibleMessages.set(Long.parseLong(visibleStr));
            }
            String notVisibleStr = attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE);
            if (notVisibleStr != null) {
                notVisibleMessages.set(Long.parseLong(notVisibleStr));
            }
            String delayedStr = attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED);
            if (delayedStr != null) {
                delayedMessages.set(Long.parseLong(delayedStr));
            }
            lastSuccessEpochSeconds.set(Instant.now().getEpochSecond());
        } catch (Exception e) {
            log.warn("SQS 메트릭 조회 실패: queueName={}, error={}", queueName, e.getMessage());
        }
    }

    private String resolveQueueUrl() {
        if (cachedQueueUrl != null) {
            return cachedQueueUrl;
        }
        try {
            var response
                = sqsAsyncClient
                    .getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build())
                    .get(3, TimeUnit.SECONDS);
            cachedQueueUrl = response.queueUrl();
            return cachedQueueUrl;
        } catch (Exception e) {
            log.warn("SQS Queue URL 조회 실패: queueName={}, error={}", queueName, e.getMessage());
            return null;
        }
    }

    public long getVisibleMessages() {
        return visibleMessages.get();
    }

    public long getNotVisibleMessages() {
        return notVisibleMessages.get();
    }

    public long getDelayedMessages() {
        return delayedMessages.get();
    }
}
