package com.backtoback.study.replica.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import com.backtoback.study.hint.service.ExamStartedConsumerService;
import com.backtoback.study.hint.service.HintBlockReplicaConsumerService;
import com.backtoback.study.hint.service.HintReadyConsumerService;
import com.backtoback.study.membership.service.MemberWithdrawnConsumerService;
import com.backtoback.study.replica.event.dto.LearningProfileUpdatedPayload;
import com.backtoback.study.replica.event.dto.MemberSuspendedPayload;
import com.backtoback.study.replica.event.dto.MemberUpdatedPayload;
import com.backtoback.study.replica.service.LearningProfileReplicaConsumerService;
import com.backtoback.study.replica.service.MemberReplicaConsumerService;
import com.backtoback.study.replica.service.SubmissionJudgedConsumerService;

import tools.jackson.databind.json.JsonMapper;

/**
 * 회원 정보·정지 이벤트가 처리 대상이 아닌 이벤트로 기록만 되던 결함의 회귀 테스트 (event-contract.md §1.1 평평한 메시지, §5).
 */
@ExtendWith(MockitoExtension.class)
class SubmissionJudgedEventListenerTest {

    private static final String LEARNING_PROFILE_EVENT_ID = "9c4f5e6d-7081-4c93-9dae-2f3a4b5c6d7e";

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
    @Mock
    private LearningProfileReplicaConsumerService learningProfileReplicaConsumerService;
    @Mock
    private CommittedEventChecker committedEventChecker;

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
                examStartedConsumerService,
                learningProfileReplicaConsumerService,
                committedEventChecker
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

    @Test
    @DisplayName("LearningProfileUpdated 메시지를 받으면 태그 레벨 배열까지 읽어 학습 프로필 소비로 넘기고 미지원 처리하지 않는다")
    void onMessage_learningProfileUpdated_delegatesToLearningProfileConsumer() {
        // given
        String message = """
            {"eventId":"8b3e4d5c-6f70-4b82-8c9d-1e2f3a4b5c6d","eventType":"LearningProfileUpdated","schemaVersion":"v1",
             "occurredAt":"2026-11-21T10:00:00.123456+09:00","aggregateId":"7","correlationId":"corr-3",
             "memberId":"7","profileVersion":4,"goal":"EMPLOYMENT","preferredLanguages":["java"],
             "activeTimeSlots":["EVENING"],"tagLevels":[{"tagId":"3","level":"LV2"},{"tagId":"5","level":"LV1"}]}
            """;

        // when
        listener.onMessage(message);

        // then
        ArgumentCaptor<LearningProfileUpdatedPayload> captor
            = ArgumentCaptor.forClass(LearningProfileUpdatedPayload.class);
        verify(learningProfileReplicaConsumerService).consume(captor.capture());
        LearningProfileUpdatedPayload payload = captor.getValue();
        assertThat(payload.memberId()).isEqualTo("7");
        assertThat(payload.profileVersion()).isEqualTo(4L);
        assertThat(payload.goal()).isEqualTo("EMPLOYMENT");
        assertThat(payload.tagLevels()).hasSize(2);
        assertThat(payload.tagLevels().get(0).tagId()).isEqualTo("3");
        assertThat(payload.tagLevels().get(0).level()).isEqualTo("LV2");
        verify(consumerService, never()).consumeUnknown(anyString(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("소비 기록 INSERT가 중복이고 같은 이벤트 기록이 커밋돼 있으면 예외 없이 성공 처리한다")
    void onMessage_duplicateAlreadyCommitted_succeeds() {
        // given
        willThrow(new DuplicateKeyException("duplicate"))
            .given(learningProfileReplicaConsumerService)
            .consume(any(LearningProfileUpdatedPayload.class));
        given(committedEventChecker.isCommitted(UUID.fromString(LEARNING_PROFILE_EVENT_ID))).willReturn(true);

        // when & then
        assertThatCode(() -> listener.onMessage(learningProfileMessage())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("유일 제약 위반인데 같은 이벤트 기록이 커밋돼 있지 않으면 재수신되도록 예외를 다시 던진다")
    void onMessage_duplicateNotCommitted_rethrows() {
        // given
        willThrow(new DuplicateKeyException("business unique"))
            .given(learningProfileReplicaConsumerService)
            .consume(any(LearningProfileUpdatedPayload.class));
        given(committedEventChecker.isCommitted(UUID.fromString(LEARNING_PROFILE_EVENT_ID))).willReturn(false);

        // when & then
        assertThatThrownBy(() -> listener.onMessage(learningProfileMessage()))
            .isInstanceOf(DuplicateKeyException.class);
    }

    private static String learningProfileMessage() {
        return """
            {"eventId":"%s","eventType":"LearningProfileUpdated","schemaVersion":"v1",
             "occurredAt":"2026-11-21T10:00:00.123456+09:00","aggregateId":"7","correlationId":"corr-4",
             "memberId":"7","profileVersion":4,"goal":"EMPLOYMENT","preferredLanguages":["java"],
             "activeTimeSlots":["EVENING"],"tagLevels":[]}
            """.formatted(LEARNING_PROFILE_EVENT_ID);
    }
}
