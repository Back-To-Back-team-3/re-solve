package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.backtoback.common.event.service.OutboxRelay;
import com.backtoback.contest.exam.dto.ExamSubmissionRequest;

import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/** 발행 설정을 활성화한 Spring 환경에서 접수·Relay·업무 콜백을 확인한다. */
@TestPropertySource(
    properties = {
        "contest.events.publish-enabled=true",
        "contest.events.topic-arn=arn:aws:sns:ap-northeast-2:000000000000:contest-events",
        "contest.events.submission-queue-url=http://sqs-test/submissions"
    }
)
class ExamOutboxRelayIntegrationTest extends ExamIntegrationTestSupport {
    @MockitoBean(name = "contestSnsClient")
    private SnsClient sns;
    @MockitoBean(name = "contestSqsClient")
    private SqsClient sqs;
    @MockitoBean
    private TaskScheduler taskScheduler;
    @Autowired
    private OutboxRelay relay;

    @Test
    @DisplayName("접수 Outbox가 Relay를 거쳐 SQS로 발행되고 발행 상태와 접수 상태를 함께 갱신한다")
    void publishesSubmissionAndUpdatesReceipt() {
        var receipt = submissions.submit(examId, "7", new ExamSubmissionRequest("101", "java", "코드", "1"));

        assertThat(relay.relayBatch()).isEqualTo(1);

        var request = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs).sendMessage(request.capture());
        assertThat(request.getValue().queueUrl()).isEqualTo("http://sqs-test/submissions");
        String payload
            = jdbc
                .queryForObject(
                    "SELECT payload FROM outbox_events WHERE aggregate_id=?",
                    String.class,
                    receipt.examSubmissionId()
                );
        assertThat(request.getValue().messageBody()).isEqualTo(payload);
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT status FROM outbox_events WHERE aggregate_id=?",
                    String.class,
                    receipt.examSubmissionId()
                )
        ).isEqualTo("PUBLISHED");
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT status FROM exam_submissions WHERE id=?",
                    String.class,
                    receipt.examSubmissionId()
                )
        ).isEqualTo("REQUESTED");
    }
}
