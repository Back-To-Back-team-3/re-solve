package com.backtoback.study.replica.event.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backtoback.common.event.dto.EventMetadata;
import com.backtoback.study.replica.domain.MemberStatus;

/**
 * MemberSuspended 페이로드 검증·상태 변환 단위 테스트 (event-contract.md §M2).
 */
class MemberSuspendedPayloadTest {

    private static final String EVENT_ID = "7a2d3c4b-5e6f-4a71-9b8c-0d1e2f3a4b5c";
    private static final String OCCURRED_AT = "2026-11-16T10:00:00.123456+09:00";

    @Test
    @DisplayName("정지 이벤트는 SUSPENDED, 해제 이벤트는 ACTIVE로 변환한다")
    void targetStatus_bySuspendedFlag() {
        assertThat(payload("7", true, 2L).targetStatus()).isEqualTo(MemberStatus.SUSPENDED);
        assertThat(payload("7", false, 3L).targetStatus()).isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    @DisplayName("계약을 지키는 페이로드는 검증을 통과한다")
    void validate_validPayload_passes() {
        MemberSuspendedPayload payload = payload("7", true, 2L);

        assertThatCode(payload::validate).doesNotThrowAnyException();
        assertThat(payload.memberIdAsLong()).isEqualTo(7L);
    }

    @Test
    @DisplayName("회원 ID가 숫자 문자열이 아니면 검증에서 예외가 발생한다")
    void validate_nonNumericMemberId_throws() {
        assertThatThrownBy(payload("member-7", true, 2L)::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("순서 값이 없거나 음수이면 검증에서 예외가 발생한다")
    void validate_invalidSourceVersion_throws() {
        assertThatThrownBy(payload("7", true, null)::validate).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(payload("7", true, -1L)::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("정지 여부가 없으면 검증에서 예외가 발생한다")
    void validate_nullSuspended_throws() {
        assertThatThrownBy(payload("7", null, 2L)::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("correlationId가 없으면 메타데이터의 correlationId를 anonymous로 대체한다")
    void toMetadata_withoutCorrelationId_usesAnonymous() {
        MemberSuspendedPayload payload = new MemberSuspendedPayload(EVENT_ID, OCCURRED_AT, " ", "7", true, 2L);

        EventMetadata metadata = payload.toMetadata();

        assertThat(metadata.correlationId()).isEqualTo("anonymous");
        assertThat(metadata.eventType()).isEqualTo("MemberSuspended");
    }

    private MemberSuspendedPayload payload(String memberId, Boolean suspended, Long sourceVersion) {
        return new MemberSuspendedPayload(EVENT_ID, OCCURRED_AT, "corr-1", memberId, suspended, sourceVersion);
    }
}
