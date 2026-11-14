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
 * 기본 큐와 DLQ를 모두 모니터링한다.
 */
@Slf4j
@Component
@ConditionalOnClass(SqsAsyncClient.class)
public class SqsQueueMetricsBinder implements MeterBinder {

    private final SqsAsyncClient sqsAsyncClient;
    private final QueueMetrics mainQueue;
    private final QueueMetrics dlqQueue;

    @Autowired
    public SqsQueueMetricsBinder(
        @Autowired(required = false) SqsAsyncClient sqsAsyncClient,
        @Value("${integration.sqs.queue-name:integration-queue}") String queueName,
        @Value("${integration.sqs.dlq-name:${integration.sqs.queue-name:integration-queue}-dlq}") String dlqQueueName
    ) {
        this.sqsAsyncClient = sqsAsyncClient;
        this.mainQueue = new QueueMetrics(queueName);
        this.dlqQueue = new QueueMetrics(dlqQueueName);
    }

    public SqsQueueMetricsBinder(SqsAsyncClient sqsAsyncClient, String queueName) {
        this(sqsAsyncClient, queueName, queueName + "-dlq");
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        boolean hasClient = sqsAsyncClient != null;
        mainQueue.bindTo(registry, hasClient);
        dlqQueue.bindTo(registry, hasClient);
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
        refreshQueue(mainQueue);
        refreshQueue(dlqQueue);
    }

    private void refreshQueue(QueueMetrics qm) {
        try {
            String queueUrl = resolveQueueUrl(qm);
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
                qm.visible.set(Long.parseLong(visibleStr));
            }
            String notVisibleStr = attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE);
            if (notVisibleStr != null) {
                qm.notVisible.set(Long.parseLong(notVisibleStr));
            }
            String delayedStr = attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED);
            if (delayedStr != null) {
                qm.delayed.set(Long.parseLong(delayedStr));
            }
            qm.lastSuccessEpochSeconds.set(Instant.now().getEpochSecond());
        } catch (Exception e) {
            log.warn("SQS 메트릭 조회 실패: queueName={}, error={}", qm.name, e.getMessage());
        }
    }

    private String resolveQueueUrl(QueueMetrics qm) {
        if (qm.cachedUrl != null) {
            return qm.cachedUrl;
        }
        try {
            var response
                = sqsAsyncClient
                    .getQueueUrl(GetQueueUrlRequest.builder().queueName(qm.name).build())
                    .get(3, TimeUnit.SECONDS);
            qm.cachedUrl = response.queueUrl();
            return qm.cachedUrl;
        } catch (Exception e) {
            log.warn("SQS Queue URL 조회 실패: queueName={}, error={}", qm.name, e.getMessage());
            return null;
        }
    }

    public long getVisibleMessages() {
        return mainQueue.visible.get();
    }

    public long getNotVisibleMessages() {
        return mainQueue.notVisible.get();
    }

    public long getDelayedMessages() {
        return mainQueue.delayed.get();
    }

    public long getDlqVisibleMessages() {
        return dlqQueue.visible.get();
    }

    private static class QueueMetrics {

        private final String name;
        private final AtomicLong visible = new AtomicLong(0);
        private final AtomicLong notVisible = new AtomicLong(0);
        private final AtomicLong delayed = new AtomicLong(0);
        private final AtomicLong lastSuccessEpochSeconds = new AtomicLong(0L);
        private volatile String cachedUrl;

        QueueMetrics(String name) {
            this.name = name;
        }

        void bindTo(MeterRegistry registry, boolean hasClient) {
            Gauge
                .builder("aws.sqs.messages.visible", visible, AtomicLong::get)
                .tag("queue", name)
                .description("Approximate number of messages available for retrieval from the SQS queue")
                .register(registry);

            Gauge
                .builder("aws.sqs.messages.not_visible", notVisible, AtomicLong::get)
                .tag("queue", name)
                .description("Approximate number of messages in flight (not visible) in the SQS queue")
                .register(registry);

            Gauge
                .builder("aws.sqs.messages.delayed", delayed, AtomicLong::get)
                .tag("queue", name)
                .description("Approximate number of messages delayed and not available for immediate retrieval")
                .register(registry);

            if (hasClient) {
                Gauge
                    .builder("aws.sqs.metrics.last.success.timestamp.seconds", lastSuccessEpochSeconds, AtomicLong::get)
                    .tag("queue", name)
                    .description("Epoch seconds of the last successful SQS queue metrics refresh")
                    .register(registry);
            }
        }
    }
}
