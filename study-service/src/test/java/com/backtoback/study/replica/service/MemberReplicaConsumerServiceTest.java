package com.backtoback.study.replica.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.common.event.dto.EventMetadata;
import com.backtoback.common.event.store.JdbcProcessedEventStore;
import com.backtoback.study.replica.domain.MemberReplica;
import com.backtoback.study.replica.domain.MemberStatus;
import com.backtoback.study.replica.event.dto.MemberSuspendedPayload;
import com.backtoback.study.replica.event.dto.MemberUpdatedPayload;
import com.backtoback.study.replica.repository.MemberReplicaRepository;

/**
 * 회원 정보·정지 이벤트 소비 분기 단위 테스트 (event-contract.md §M1·§M2, §1.4 멱등, §1.5 순서 값 규칙).
 */
@ExtendWith(MockitoExtension.class)
class MemberReplicaConsumerServiceTest {

    private static final String EVENT_ID = "6f1c2b3a-4d5e-4f60-8a7b-9c0d1e2f3a4b";
    private static final String OCCURRED_AT = "2026-11-16T10:00:00.123456+09:00";
    private static final Long MEMBER_ID = 7L;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-11-16T01:05:00.123456789Z");
    private static final LocalDateTime NOW_MICROS = LocalDateTime.of(2026, 11, 16, 10, 5, 0, 123_456_000);

    @Spy
    private Clock clock = Clock.fixed(NOW, SEOUL);
    @Mock
    private JdbcProcessedEventStore processedEventStore;
    @Mock
    private MemberReplicaRepository memberReplicaRepository;

    @InjectMocks
    private MemberReplicaConsumerService service;

    @Test
    @DisplayName("회원 정보 변경 이벤트를 처리하면 소비 기록 후 마이크로초로 자른 처리 시각으로 복제본 반영을 요청한다")
    void consumeUpdated_validPayload_upserts() {
        // when
        service.consumeUpdated(updated("ACTIVE", "닉네임"));

        // then
        verify(processedEventStore).insert(any(EventMetadata.class), eq(NOW_MICROS));
        verify(memberReplicaRepository)
            .upsertFromMemberUpdated(MEMBER_ID, "닉네임", "https://img/1.png", "ACTIVE", 3L, NOW_MICROS);
    }

    @Test
    @DisplayName("정지 상태가 실린 회원 정보 변경 이벤트는 SUSPENDED로 반영을 요청한다")
    void consumeUpdated_suspendedStatus_upsertsSuspended() {
        // when
        service.consumeUpdated(updated("SUSPENDED", "닉네임"));

        // then
        verify(memberReplicaRepository)
            .upsertFromMemberUpdated(MEMBER_ID, "닉네임", "https://img/1.png", "SUSPENDED", 3L, NOW_MICROS);
    }

    @Test
    @DisplayName("이미 처리한 회원 정보 변경 이벤트는 복제본에 반영하지 않는다")
    void consumeUpdated_duplicateEventId_skips() {
        // given
        given(processedEventStore.exists(any(UUID.class))).willReturn(true);

        // when
        service.consumeUpdated(updated("ACTIVE", "닉네임"));

        // then
        verify(memberReplicaRepository, never())
            .upsertFromMemberUpdated(anyLong(), anyString(), any(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("계약과 다른 회원 정보 변경 이벤트는 예외를 던지고 복제본에 반영하지 않는다")
    void consumeUpdated_invalidPayload_throws() {
        // when & then
        assertThatThrownBy(() -> service.consumeUpdated(updated("ACTIVE", " ")))
            .isInstanceOf(IllegalArgumentException.class);
        verify(memberReplicaRepository, never())
            .upsertFromMemberUpdated(anyLong(), anyString(), any(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("탈퇴 상태가 실린 회원 정보 변경 이벤트는 소비 기록만 남기고 복제본에 반영하지 않는다")
    void consumeUpdated_withdrawnStatus_recordsOnly() {
        // when
        assertThatCode(() -> service.consumeUpdated(updated("WITHDRAWN", "닉네임"))).doesNotThrowAnyException();

        // then
        verify(processedEventStore).insert(any(EventMetadata.class), any(LocalDateTime.class));
        verify(memberReplicaRepository, never())
            .upsertFromMemberUpdated(anyLong(), anyString(), any(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("정지 이벤트를 처리하면 SUSPENDED로 상태 반영을 요청하고 반영되면 복제본을 다시 조회하지 않는다")
    void consumeSuspended_suspended_updatesStatus() {
        // given
        given(memberReplicaRepository.updateStatusFromMemberSuspended(MEMBER_ID, "SUSPENDED", 4L, NOW_MICROS))
            .willReturn(1);

        // when
        service.consumeSuspended(suspended(true));

        // then
        verify(memberReplicaRepository).updateStatusFromMemberSuspended(MEMBER_ID, "SUSPENDED", 4L, NOW_MICROS);
        verify(memberReplicaRepository, never()).findByMemberId(anyLong());
    }

    @Test
    @DisplayName("정지 해제 이벤트를 처리하면 ACTIVE로 상태 반영을 요청한다")
    void consumeSuspended_resumed_updatesActive() {
        // given
        given(memberReplicaRepository.updateStatusFromMemberSuspended(MEMBER_ID, "ACTIVE", 4L, NOW_MICROS))
            .willReturn(1);

        // when
        service.consumeSuspended(suspended(false));

        // then
        verify(memberReplicaRepository).updateStatusFromMemberSuspended(MEMBER_ID, "ACTIVE", 4L, NOW_MICROS);
    }

    @Test
    @DisplayName("복제본이 없는 회원의 정지 이벤트는 예외 없이 처리 기록만 남긴다")
    void consumeSuspended_noReplica_recordsOnly() {
        // given
        given(memberReplicaRepository.updateStatusFromMemberSuspended(MEMBER_ID, "SUSPENDED", 4L, NOW_MICROS))
            .willReturn(0);
        given(memberReplicaRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.empty());

        // when & then
        assertThatCode(() -> service.consumeSuspended(suspended(true))).doesNotThrowAnyException();
        verify(processedEventStore).insert(any(EventMetadata.class), any(LocalDateTime.class));
        verify(memberReplicaRepository).findByMemberId(MEMBER_ID);
    }

    @Test
    @DisplayName("복제본이 있는데 반영되지 않은 정지 이벤트는 지연·탈퇴 이벤트로 보고 예외 없이 넘어간다")
    void consumeSuspended_staleOrWithdrawn_ignored() {
        // given
        MemberReplica replica = MemberReplica.sync(MEMBER_ID, "닉네임", null, MemberStatus.ACTIVE, 5L, NOW_MICROS);
        given(memberReplicaRepository.updateStatusFromMemberSuspended(MEMBER_ID, "SUSPENDED", 4L, NOW_MICROS))
            .willReturn(0);
        given(memberReplicaRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(replica));

        // when & then
        assertThatCode(() -> service.consumeSuspended(suspended(true))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("이미 처리한 정지 이벤트는 복제본에 반영하지 않는다")
    void consumeSuspended_duplicateEventId_skips() {
        // given
        given(processedEventStore.exists(any(UUID.class))).willReturn(true);

        // when
        service.consumeSuspended(suspended(true));

        // then
        verify(memberReplicaRepository, never())
            .updateStatusFromMemberSuspended(anyLong(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("계약과 다른 정지 이벤트는 예외를 던지고 복제본에 반영하지 않는다")
    void consumeSuspended_invalidPayload_throws() {
        // given
        MemberSuspendedPayload payload = new MemberSuspendedPayload(EVENT_ID, OCCURRED_AT, "corr-1", "7", null, 4L);

        // when & then
        assertThatThrownBy(() -> service.consumeSuspended(payload)).isInstanceOf(IllegalArgumentException.class);
        verify(memberReplicaRepository, never())
            .updateStatusFromMemberSuspended(anyLong(), anyString(), anyLong(), any());
    }

    private MemberUpdatedPayload updated(String status, String nickname) {
        return new MemberUpdatedPayload(
            EVENT_ID,
            OCCURRED_AT,
            "corr-1",
            String.valueOf(MEMBER_ID),
            3L,
            status,
            nickname,
            "https://img/1.png"
        );
    }

    private MemberSuspendedPayload suspended(boolean suspended) {
        return new MemberSuspendedPayload(EVENT_ID, OCCURRED_AT, "corr-1", String.valueOf(MEMBER_ID), suspended, 4L);
    }
}
