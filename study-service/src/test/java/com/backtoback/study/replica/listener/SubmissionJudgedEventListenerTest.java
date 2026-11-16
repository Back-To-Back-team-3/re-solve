package com.backtoback.study.replica.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.study.hint.service.ExamStartedConsumerService;
import com.backtoback.study.hint.service.HintBlockReplicaConsumerService;
import com.backtoback.study.hint.service.HintReadyConsumerService;
import com.backtoback.study.membership.service.MemberWithdrawnConsumerService;
import com.backtoback.study.replica.event.dto.MemberSuspendedPayload;
import com.backtoback.study.replica.event.dto.MemberUpdatedPayload;
import com.backtoback.study.replica.service.MemberReplicaConsumerService;
import com.backtoback.study.replica.service.SubmissionJudgedConsumerService;

import tools.jackson.databind.json.JsonMapper;

/**
 * 회원 정보·정지 이벤트가 처리 대상이 아닌 이벤트로 기록만 되던 결함의 회귀 테스트 (event-contract.md §1.1 평평한 메시지, §5).
 */
@ExtendWith(MockitoExtension.class)
class SubmissionJudgedEventListenerTest {

    @Mock
    private MemberWithdrawnConsumerService memberWithdrawnConsumerService;
    @Mock
    private MemberReplicaConsumerService memberReplicaConsumerService;
    @Mock
    private SubmissionJudgedConsumerService consumerService;
    @Mock
    private HintBlockReplicaConsumerService hintBlockReplicaConsumerService;
    @Mock
    private HintReadyConsumerService hintReadyConsumerService;
    @Mock
    private ExamStartedConsumerService examStartedConsumerService;

    private SubmissionJudgedEventListener listener;

    @BeforeEach
    void setUp() {
        listener
            = new SubmissionJudgedEventListener(
                JsonMapper.builder().build(),
                memberWithdrawnConsumerService,
                memberReplicaConsumerService,
                consumerService,
                hintBlockReplicaConsumerService,
                hintReadyConsumerService,
                examStartedConsumerService
            );
    }

    @Test
    @DisplayName("MemberUpdated 메시지를 받으면 헤더와 페이로드를 함께 읽어 회원 정보 소비로 넘기고 미지원 처리하지 않는다")
    void onMessage_memberUpdated_delegatesToReplicaConsumer() {
        // given
        String message = """
            {"eventId":"6f1c2b3a-4d5e-4f60-8a7b-9c0d1e2f3a4b","eventType":"MemberUpdated","schemaVersion":"v1",
             "occurredAt":"2026-11-16T10:00:00.123456+09:00","aggregateId":"7","correlationId":"corr-1",
             "memberId":"7","sourceVersion":3,"status":"ACTIVE","nickname":"닉네임","profileImageUrl":null}
            """;

        // when
        listener.onMessage(message);

        // then
        ArgumentCaptor<MemberUpdatedPayload> captor = ArgumentCaptor.forClass(MemberUpdatedPayload.class);
        verify(memberReplicaConsumerService).consumeUpdated(captor.capture());
        MemberUpdatedPayload payload = captor.getValue();
        assertThat(payload.eventId()).isEqualTo("6f1c2b3a-4d5e-4f60-8a7b-9c0d1e2f3a4b");
        assertThat(payload.correlationId()).isEqualTo("corr-1");
        assertThat(payload.memberId()).isEqualTo("7");
        assertThat(payload.sourceVersion()).isEqualTo(3L);
        assertThat(payload.nickname()).isEqualTo("닉네임");
        verify(consumerService, never()).consumeUnknown(anyString(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("MemberSuspended 메시지를 받으면 사용하지 않는 사유 필드를 무시하고 회원 정지 소비로 넘긴다")
    void onMessage_memberSuspended_delegatesToReplicaConsumer() {
        // given
        String message = """
            {"eventId":"7a2d3c4b-5e6f-4a71-9b8c-0d1e2f3a4b5c","eventType":"MemberSuspended","schemaVersion":"v1",
             "occurredAt":"2026-11-16T10:00:00.123456+09:00","aggregateId":"7","correlationId":"corr-2",
             "memberId":"7","suspended":true,"sourceVersion":4,"reason":"운영 사유",
             "changedAt":"2026-11-16T10:00:00.123456+09:00"}
            """;

        // when
        listener.onMessage(message);

        // then
        ArgumentCaptor<MemberSuspendedPayload> captor = ArgumentCaptor.forClass(MemberSuspendedPayload.class);
        verify(memberReplicaConsumerService).consumeSuspended(captor.capture());
        MemberSuspendedPayload payload = captor.getValue();
        assertThat(payload.suspended()).isTrue();
        assertThat(payload.sourceVersion()).isEqualTo(4L);
        verify(consumerService, never()).consumeUnknown(anyString(), anyString(), any(), any(), any());
    }
}
