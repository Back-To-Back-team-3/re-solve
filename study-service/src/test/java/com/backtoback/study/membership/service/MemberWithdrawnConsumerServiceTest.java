package com.backtoback.study.membership.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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
import com.backtoback.common.event.store.JdbcProcessedEventStore;
import com.backtoback.study.membership.domain.MembershipRole;
import com.backtoback.study.membership.domain.MembershipStatus;
import com.backtoback.study.membership.domain.StudyMembership;
import com.backtoback.study.membership.repository.StudyMembershipHistoryRepository;
import com.backtoback.study.membership.repository.StudyMembershipRepository;
import com.backtoback.study.replica.domain.MemberReplica;
import com.backtoback.study.replica.event.dto.MemberWithdrawnPayload;
import com.backtoback.study.replica.repository.MemberLearningProfileReplicaRepository;
import com.backtoback.study.replica.repository.MemberReplicaRepository;

/**
 * MemberWithdrawn 소비 케이스별 단위 테스트 (event-contract.md §M3, §1.4 멱등, §1.5 순서 값 규칙).
 */
@ExtendWith(MockitoExtension.class)
class MemberWithdrawnConsumerServiceTest {

    private static final Long MEMBER_ID = 1L;
    private static final String WITHDRAWN_AT = "2026-11-10T10:00:00+09:00";

    @Spy
    private Clock clock = Clock.systemDefaultZone();
    @Mock
    private JdbcProcessedEventStore processedEventStore;
    @Mock
    private StudyMembershipCommandService membershipCommandService;
    @Mock
    private StudyMembershipRepository membershipRepository;
    @Mock
    private StudyMembershipHistoryRepository historyRepository;
    @Mock
    private MemberReplicaRepository memberReplicaRepository;
    @Mock
    private MemberLearningProfileReplicaRepository learningProfileReplicaRepository;

    @InjectMocks
    private MemberWithdrawnConsumerService service;

    @Test
    @DisplayName("APPROVED 2건·PENDING 1건인 회원의 MemberWithdrawn을 처리하면 각각 탈퇴·취소·복제본 삭제를 수행한다")
    void consume_approvedAndPendingMemberships_processesAll() {
        // given
        MemberWithdrawnPayload payload = payload(10L);

        StudyMembership approved1 = mock(StudyMembership.class);
        StudyMembership approved2 = mock(StudyMembership.class);
        StudyMembership pending1 = pendingMembership(3L);

        given(memberReplicaRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.empty());
        given(membershipRepository.findAllByMemberIdAndStatus(MEMBER_ID, MembershipStatus.APPROVED))
            .willReturn(List.of(approved1, approved2));
        given(membershipRepository.findAllByMemberIdAndStatus(MEMBER_ID, MembershipStatus.PENDING))
            .willReturn(List.of(pending1));

        // when
        service.consume(payload);

        // then
        verify(membershipCommandService, times(2))
            .applyLeaveInternal(
                any(StudyMembership.class),
                eq("MEMBER_WITHDRAWN"),
                eq(payload.eventId()),
                any(LocalDateTime.class)
            );
        verify(membershipRepository).cancelIfPending(pending1.getId());
        verify(historyRepository).save(any());
        verify(learningProfileReplicaRepository).deleteByMemberId(MEMBER_ID);
        verify(memberReplicaRepository).upsertWithdrawn(eq(MEMBER_ID), eq(10L), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("가입이 없는 회원의 MemberWithdrawn을 처리하면 복제본 정리만 수행한다")
    void consume_noMemberships_onlyCleanupReplicas() {
        // given
        MemberWithdrawnPayload payload = payload(5L);

        given(memberReplicaRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.empty());
        given(membershipRepository.findAllByMemberIdAndStatus(MEMBER_ID, MembershipStatus.APPROVED))
            .willReturn(List.of());
        given(membershipRepository.findAllByMemberIdAndStatus(MEMBER_ID, MembershipStatus.PENDING))
            .willReturn(List.of());

        // when
        service.consume(payload);

        // then
        verify(membershipCommandService, never()).applyLeaveInternal(any(), any(), any(), any());
        verify(historyRepository, never()).save(any());
        verify(learningProfileReplicaRepository).deleteByMemberId(MEMBER_ID);
        verify(memberReplicaRepository).upsertWithdrawn(eq(MEMBER_ID), eq(5L), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("이미 처리된 eventId를 수신하면 가입 정리 없이 무시한다")
    void consume_duplicateEventId_skipsProcessing() {
        // given
        MemberWithdrawnPayload payload = payload(3L);
        willThrow(new DuplicateKeyException("중복"))
            .given(processedEventStore)
            .insert(any(EventMetadata.class), any(LocalDateTime.class));

        // when
        service.consume(payload);

        // then
        verify(membershipRepository, never()).findAllByMemberIdAndStatus(any(), any());
        verify(memberReplicaRepository, never()).upsertWithdrawn(any(), any(long.class), any());
    }

    @Test
    @DisplayName("sourceVersion이 저장값 이하인 지연 이벤트는 가입 정리 없이 무시한다")
    void consume_staleSourceVersion_skipsProcessing() {
        // given
        MemberWithdrawnPayload payload = payload(3L);
        MemberReplica existingReplica = mock(MemberReplica.class);
        given(existingReplica.getSourceVersion()).willReturn(5L);
        given(memberReplicaRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(existingReplica));

        // when
        service.consume(payload);

        // then
        verify(membershipRepository, never()).findAllByMemberIdAndStatus(any(), any());
        verify(memberReplicaRepository, never()).upsertWithdrawn(any(), any(long.class), any());
    }

    private static MemberWithdrawnPayload payload(long sourceVersion) {
        return new MemberWithdrawnPayload(
            UUID.randomUUID().toString(),
            String.valueOf(MEMBER_ID),
            WITHDRAWN_AT,
            sourceVersion
        );
    }

    private static StudyMembership pendingMembership(Long id) {
        StudyMembership m = mock(StudyMembership.class);
        given(m.getId()).willReturn(id);
        given(m.getRole()).willReturn(MembershipRole.MEMBER);
        return m;
    }
}
