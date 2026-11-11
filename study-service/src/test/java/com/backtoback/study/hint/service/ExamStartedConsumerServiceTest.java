package com.backtoback.study.hint.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import com.backtoback.common.event.dto.EventMetadata;
import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.common.event.store.JdbcProcessedEventStore;
import com.backtoback.study.hint.domain.HintBlockContextType;
import com.backtoback.study.hint.domain.HintBlockReplica;
import com.backtoback.study.hint.domain.HintRequest;
import com.backtoback.study.hint.domain.HintRequestStatus;
import com.backtoback.study.hint.event.dto.ExamStartedPayload;
import com.backtoback.study.hint.repository.HintBlockReplicaRepository;
import com.backtoback.study.hint.repository.HintRequestRepository;

/**
 * ExamStarted 소비 케이스별 단위 테스트 (event-contract.md §C8, §1.4 멱등 처리).
 */
@ExtendWith(MockitoExtension.class)
class ExamStartedConsumerServiceTest {

    private static final Long EXAM_ID = 500L;
    private static final Long MEMBER_ID = 10L;
    private static final Long PROBLEM_ID = 100L;
    private static final Long HINT_REQUEST_ID = 1001L;
    private static final Long PROBLEM_REVISION_ID = 200L;
    private static final int LEVEL = 1;
    private static final String STARTS_AT = "2026-11-11T09:00:00+09:00";

    @Spy
    private Clock clock = Clock.systemDefaultZone();
    @Mock
    private JdbcProcessedEventStore processedEventStore;
    @Mock
    private HintBlockReplicaRepository hintBlockReplicaRepository;
    @Mock
    private HintRequestRepository hintRequestRepository;
    @Mock
    private OutboxEventWriter outboxEventWriter;

    @InjectMocks
    private ExamStartedConsumerService service;

    @Test
    @DisplayName("ExamStarted 수신 시 GENERATING 힌트가 존재하면 BLOCKED로 전이하고 HintReady Outbox를 발행한다")
    void consume_generatingHintExists_blocksAndPublishesOutbox() {
        // given
        ExamStartedPayload payload = payload();
        HintBlockReplica replica = stubReplica();
        HintRequest hintRequest = stubHintRequest();
        given(hintRequest.getMemberId()).willReturn(MEMBER_ID);
        given(hintRequest.getProblemId()).willReturn(PROBLEM_ID);
        given(hintRequest.getProblemRevisionId()).willReturn(PROBLEM_REVISION_ID);
        given(hintRequest.getLevel()).willReturn(LEVEL);
        given(hintRequest.isCached()).willReturn(false);

        given(hintBlockReplicaRepository.findByContextTypeAndContextId(HintBlockContextType.EXAM, EXAM_ID))
            .willReturn(List.of(replica));
        given(
            hintRequestRepository
                .findByMemberIdAndProblemIdAndStatus(MEMBER_ID, PROBLEM_ID, HintRequestStatus.GENERATING)
        ).willReturn(List.of(hintRequest));
        given(hintRequestRepository.blockIfGeneratingById(eq(HINT_REQUEST_ID), eq("EXAM"), any(LocalDateTime.class)))
            .willReturn(1);

        // when
        service.consume(payload);

        // then
        verify(hintRequestRepository).blockIfGeneratingById(eq(HINT_REQUEST_ID), eq("EXAM"), any(LocalDateTime.class));
        verify(outboxEventWriter)
            .append(anyString(), eq("HintReady"), anyString(), eq(String.valueOf(HINT_REQUEST_ID)), any(), any());
    }

    @Test
    @DisplayName("GENERATING 힌트가 없으면 상태 갱신과 Outbox 발행 없이 종료한다")
    void consume_noGeneratingHint_skipsUpdateAndOutbox() {
        // given
        ExamStartedPayload payload = payload();
        HintBlockReplica replica = stubReplica();

        given(hintBlockReplicaRepository.findByContextTypeAndContextId(HintBlockContextType.EXAM, EXAM_ID))
            .willReturn(List.of(replica));
        given(
            hintRequestRepository
                .findByMemberIdAndProblemIdAndStatus(MEMBER_ID, PROBLEM_ID, HintRequestStatus.GENERATING)
        ).willReturn(List.of());

        // when
        service.consume(payload);

        // then
        verify(hintRequestRepository, never()).blockIfGeneratingById(any(), any(), any());
        verify(outboxEventWriter, never()).append(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("blockIfGeneratingById가 0을 반환하면 경쟁 처리 완료로 간주하고 Outbox를 발행하지 않는다")
    void consume_blockReturnsZeroRows_skipsOutbox() {
        // given
        ExamStartedPayload payload = payload();
        HintBlockReplica replica = stubReplica();
        HintRequest hintRequest = stubHintRequest();

        given(hintBlockReplicaRepository.findByContextTypeAndContextId(HintBlockContextType.EXAM, EXAM_ID))
            .willReturn(List.of(replica));
        given(
            hintRequestRepository
                .findByMemberIdAndProblemIdAndStatus(MEMBER_ID, PROBLEM_ID, HintRequestStatus.GENERATING)
        ).willReturn(List.of(hintRequest));
        given(hintRequestRepository.blockIfGeneratingById(eq(HINT_REQUEST_ID), eq("EXAM"), any(LocalDateTime.class)))
            .willReturn(0);

        // when
        service.consume(payload);

        // then
        verify(outboxEventWriter, never()).append(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("이미 처리된 eventId를 수신하면 조회·갱신·Outbox 발행 없이 무시한다")
    void consume_duplicateEventId_skipsAllProcessing() {
        // given
        ExamStartedPayload payload = payload();
        willThrow(new DuplicateKeyException("중복"))
            .given(processedEventStore)
            .insert(any(EventMetadata.class), any(LocalDateTime.class));

        // when
        service.consume(payload);

        // then
        verify(hintBlockReplicaRepository, never()).findByContextTypeAndContextId(any(), any());
        verify(outboxEventWriter, never()).append(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("hint_block_replicas에 대상 행이 없으면 Outbox를 발행하지 않는다")
    void consume_noBlockReplicas_skipsOutbox() {
        // given
        ExamStartedPayload payload = payload();
        given(hintBlockReplicaRepository.findByContextTypeAndContextId(HintBlockContextType.EXAM, EXAM_ID))
            .willReturn(List.of());

        // when
        service.consume(payload);

        // then
        verify(hintRequestRepository, never()).findByMemberIdAndProblemIdAndStatus(any(), any(), any());
        verify(outboxEventWriter, never()).append(any(), any(), any(), any(), any(), any());
    }

    private ExamStartedPayload payload() {
        return new ExamStartedPayload(
            UUID.randomUUID().toString(),
            "2026-11-11T00:00:00.000000+09:00",
            String.valueOf(EXAM_ID),
            String.valueOf(EXAM_ID),
            "10",
            "1",
            STARTS_AT,
            "2026-11-11T11:00:00+09:00"
        );
    }

    private HintBlockReplica stubReplica() {
        HintBlockReplica replica = mock(HintBlockReplica.class);
        given(replica.getMemberId()).willReturn(MEMBER_ID);
        given(replica.getProblemId()).willReturn(PROBLEM_ID);
        return replica;
    }

    private HintRequest stubHintRequest() {
        HintRequest hintRequest = mock(HintRequest.class);
        given(hintRequest.getId()).willReturn(HINT_REQUEST_ID);
        return hintRequest;
    }
}
