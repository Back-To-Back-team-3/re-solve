package com.backtoback.study.replica.event.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backtoback.common.event.dto.EventMetadata;
import com.backtoback.study.replica.event.dto.LearningProfileUpdatedPayload.TagLevel;

/**
 * LearningProfileUpdated 페이로드 검증·메타데이터 변환 단위 테스트 (event-contract.md §M4, ERD 4.5.10 컬럼 길이).
 */
class LearningProfileUpdatedPayloadTest {

    private static final String EVENT_ID = "8b3e4d5c-6f70-4b82-8c9d-1e2f3a4b5c6d";
    private static final String OCCURRED_AT = "2026-11-21T10:00:00.123456+09:00";
    private static final List<TagLevel> TAG_LEVELS = List.of(new TagLevel("3", "LV2"));

    @Test
    @DisplayName("계약을 지키는 페이로드는 검증을 통과하고 회원 ID를 변환한다")
    void validate_validPayload_passes() {
        LearningProfileUpdatedPayload payload
            = payload("7", 4L, "EMPLOYMENT", List.of("java"), List.of("EVENING"), TAG_LEVELS);

        assertThatCode(payload::validate).doesNotThrowAnyException();
        assertThat(payload.memberIdAsLong()).isEqualTo(7L);
    }

    @Test
    @DisplayName("학습 목표가 없고 목록이 모두 비어 있어도 검증을 통과한다")
    void validate_nullGoalAndEmptyLists_passes() {
        LearningProfileUpdatedPayload payload = payload("7", 1L, null, List.of(), List.of(), List.of());

        assertThatCode(payload::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("계약에 없는 학습 목표 값이어도 형식이 맞으면 검증을 통과한다")
    void validate_unknownGoalValue_passes() {
        LearningProfileUpdatedPayload payload
            = payload("7", 1L, "NEW_GOAL", List.of("kotlin"), List.of("DAWN"), TAG_LEVELS);

        assertThatCode(payload::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("회원 ID가 숫자 문자열이 아니면 검증에서 예외가 발생한다")
    void validate_nonNumericMemberId_throws() {
        LearningProfileUpdatedPayload payload
            = payload("abc", 1L, "EMPLOYMENT", List.of("java"), List.of("EVENING"), TAG_LEVELS);

        assertThatThrownBy(payload::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("프로필 버전이 1보다 작으면 검증에서 예외가 발생한다")
    void validate_zeroProfileVersion_throws() {
        LearningProfileUpdatedPayload payload
            = payload("7", 0L, "EMPLOYMENT", List.of("java"), List.of("EVENING"), TAG_LEVELS);

        assertThatThrownBy(payload::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("프로필 버전이 없으면 검증에서 예외가 발생한다")
    void validate_nullProfileVersion_throws() {
        LearningProfileUpdatedPayload payload
            = payload("7", null, "EMPLOYMENT", List.of("java"), List.of("EVENING"), TAG_LEVELS);

        assertThatThrownBy(payload::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("학습 목표가 30자를 넘으면 검증에서 예외가 발생한다")
    void validate_tooLongGoal_throws() {
        LearningProfileUpdatedPayload payload
            = payload("7", 1L, "A".repeat(31), List.of("java"), List.of("EVENING"), TAG_LEVELS);

        assertThatThrownBy(payload::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("필수 목록 필드가 없으면 검증에서 예외가 발생한다")
    void validate_nullRequiredList_throws() {
        LearningProfileUpdatedPayload payload = payload("7", 1L, "EMPLOYMENT", null, List.of("EVENING"), TAG_LEVELS);

        assertThatThrownBy(payload::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("태그 레벨 항목에 태그 ID가 없으면 검증에서 예외가 발생한다")
    void validate_blankTagId_throws() {
        LearningProfileUpdatedPayload payload
            = payload("7", 1L, "EMPLOYMENT", List.of("java"), List.of("EVENING"), List.of(new TagLevel(" ", "LV1")));

        assertThatThrownBy(payload::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("correlationId가 없으면 anonymous로 대체한 소비 메타데이터를 만든다")
    void toMetadata_nullCorrelationId_usesAnonymous() {
        LearningProfileUpdatedPayload payload
            = new LearningProfileUpdatedPayload(
                EVENT_ID,
                OCCURRED_AT,
                null,
                "7",
                1L,
                "EMPLOYMENT",
                List.of("java"),
                List.of("EVENING"),
                TAG_LEVELS
            );

        EventMetadata metadata = payload.toMetadata();

        assertThat(metadata.eventType()).isEqualTo("LearningProfileUpdated");
        assertThat(metadata.schemaVersion()).isEqualTo("v1");
        assertThat(metadata.aggregateId()).isEqualTo("7");
        assertThat(metadata.correlationId()).isEqualTo("anonymous");
    }

    private static LearningProfileUpdatedPayload payload(
        String memberId,
        Long profileVersion,
        String goal,
        List<String> preferredLanguages,
        List<String> activeTimeSlots,
        List<TagLevel> tagLevels
    ) {
        return new LearningProfileUpdatedPayload(
            EVENT_ID,
            OCCURRED_AT,
            "corr-1",
            memberId,
            profileVersion,
            goal,
            preferredLanguages,
            activeTimeSlots,
            tagLevels
        );
    }
}
