package com.backtoback.study.replica.event.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backtoback.common.event.dto.EventMetadata;
import com.backtoback.study.replica.domain.MemberStatus;

/**
 * MemberUpdated 페이로드 검증·메타데이터 변환 단위 테스트 (event-contract.md §M1, ERD 3.5 컬럼 길이).
 */
class MemberUpdatedPayloadTest {

    private static final String EVENT_ID = "6f1c2b3a-4d5e-4f60-8a7b-9c0d1e2f3a4b";
    private static final String OCCURRED_AT = "2026-11-16T10:00:00.123456+09:00";

    @Test
    @DisplayName("계약을 지키는 페이로드는 검증을 통과하고 상태와 회원 ID를 변환한다")
    void validate_validPayload_passes() {
        MemberUpdatedPayload payload = payload("7", 3L, "ACTIVE", "닉네임", null);

        assertThatCode(payload::validate).doesNotThrowAnyException();
        assertThat(payload.memberIdAsLong()).isEqualTo(7L);
        assertThat(payload.memberStatus()).isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    @DisplayName("닉네임이 50자이고 이미지 주소가 500자이면 검증을 통과한다")
    void validate_maxLength_passes() {
        MemberUpdatedPayload payload = payload("7", 1L, "SUSPENDED", "가".repeat(50), "a".repeat(500));

        assertThatCode(payload::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("닉네임이 비어 있으면 검증에서 예외가 발생한다")
    void validate_blankNickname_throws() {
        MemberUpdatedPayload payload = payload("7", 1L, "ACTIVE", " ", null);

        assertThatThrownBy(payload::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("닉네임이 51자이면 검증에서 예외가 발생한다")
    void validate_tooLongNickname_throws() {
        MemberUpdatedPayload payload = payload("7", 1L, "ACTIVE", "가".repeat(51), null);

        assertThatThrownBy(payload::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("이미지 주소가 501자이면 검증에서 예외가 발생한다")
    void validate_tooLongProfileImageUrl_throws() {
        MemberUpdatedPayload payload = payload("7", 1L, "ACTIVE", "닉네임", "a".repeat(501));

        assertThatThrownBy(payload::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("계약에 없는 상태 값이면 검증에서 예외가 발생한다")
    void validate_unknownStatus_throws() {
        MemberUpdatedPayload payload = payload("7", 1L, "DORMANT", "닉네임", null);

        assertThatThrownBy(payload::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("상태 값이 없으면 검증에서 예외가 발생한다")
    void validate_nullStatus_throws() {
        MemberUpdatedPayload payload = payload("7", 1L, null, "닉네임", null);

        assertThatThrownBy(payload::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("회원 ID가 숫자 문자열이 아니면 검증에서 예외가 발생한다")
    void validate_nonNumericMemberId_throws() {
        MemberUpdatedPayload payload = payload("member-7", 1L, "ACTIVE", "닉네임", null);

        assertThatThrownBy(payload::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("순서 값이 없거나 음수이면 검증에서 예외가 발생한다")
    void validate_invalidSourceVersion_throws() {
        assertThatThrownBy(payload("7", null, "ACTIVE", "닉네임", null)::validate)
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(payload("7", -1L, "ACTIVE", "닉네임", null)::validate)
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("correlationId가 없으면 메타데이터의 correlationId를 anonymous로 대체한다")
    void toMetadata_withoutCorrelationId_usesAnonymous() {
        MemberUpdatedPayload payload
            = new MemberUpdatedPayload(EVENT_ID, OCCURRED_AT, null, "7", 1L, "ACTIVE", "닉네임", null);

        EventMetadata metadata = payload.toMetadata();

        assertThat(metadata.correlationId()).isEqualTo("anonymous");
        assertThat(metadata.eventType()).isEqualTo("MemberUpdated");
        assertThat(metadata.aggregateId()).isEqualTo("7");
    }

    @Test
    @DisplayName("correlationId가 있으면 메타데이터에 그대로 전달한다")
    void toMetadata_withCorrelationId_keepsHeader() {
        EventMetadata metadata = payload("7", 1L, "ACTIVE", "닉네임", null).toMetadata();

        assertThat(metadata.correlationId()).isEqualTo("corr-1");
    }

    private MemberUpdatedPayload payload(
        String memberId,
        Long sourceVersion,
        String status,
        String nickname,
        String profileImageUrl
    ) {
        return new MemberUpdatedPayload(
            EVENT_ID,
            OCCURRED_AT,
            "corr-1",
            memberId,
            sourceVersion,
            status,
            nickname,
            profileImageUrl
        );
    }
}
