package com.backtoback.study.replica.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import com.backtoback.common.event.dto.EventMetadata;
import com.backtoback.common.event.store.JdbcProcessedEventStore;
import com.backtoback.study.replica.domain.MemberReplica;
import com.backtoback.study.replica.domain.MemberStatus;
import com.backtoback.study.replica.event.dto.LearningProfileUpdatedPayload;
import com.backtoback.study.replica.event.dto.LearningProfileUpdatedPayload.TagLevel;
import com.backtoback.study.replica.repository.MemberLearningProfileReplicaRepository;
import com.backtoback.study.replica.repository.MemberReplicaRepository;

import tools.jackson.databind.json.JsonMapper;

/**
 * 학습 프로필 변경 이벤트 소비 분기 단위 테스트 (event-contract.md §M4, §1.4 멱등, §1.5 순서 값 규칙, ERD 4.5.10).
 */
@ExtendWith(MockitoExtension.class)
class LearningProfileReplicaConsumerServiceTest {

    private static final String EVENT_ID = "8b3e4d5c-6f70-4b82-8c9d-1e2f3a4b5c6d";
    private static final String OCCURRED_AT = "2026-11-21T10:00:00.123456+09:00";
    private static final Long MEMBER_ID = 7L;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-11-21T01:05:00.123456789Z");
    private static final LocalDateTime NOW_MICROS = LocalDateTime.of(2026, 11, 21, 10, 5, 0, 123_456_000);

    @Mock
    private JdbcProcessedEventStore processedEventStore;
    @Mock
    private MemberReplicaRepository memberReplicaRepository;
    @Mock
    private MemberLearningProfileReplicaRepository learningProfileReplicaRepository;

    private LearningProfileReplicaConsumerService service;

    @BeforeEach
    void setUp() {
        service
            = new LearningProfileReplicaConsumerService(
                Clock.fixed(NOW, SEOUL),
                JsonMapper.builder().build(),
                processedEventStore,
                memberReplicaRepository,
                learningProfileReplicaRepository
            );
    }

    @Test
    @DisplayName("학습 프로필 이벤트를 처리하면 소비 기록 후 태그 레벨을 tagId 맵으로 바꿔 복제본 교체를 요청한다")
    void consume_validPayload_upsertsWithTagIdMap() {
        // given
        given(memberReplicaRepository.findByMemberIdWithForShare(MEMBER_ID)).willReturn(Optional.empty());

        // when
        service
            .consume(
                payload(
                    "EMPLOYMENT",
                    List.of("java", "python3"),
                    List.of("EVENING"),
                    List.of(new TagLevel("3", "LV2"), new TagLevel("5", "LV1"))
                )
            );

        // then
        verify(processedEventStore).insert(any(EventMetadata.class), eq(NOW_MICROS));
        verify(learningProfileReplicaRepository)
            .upsertFromLearningProfileUpdated(
                MEMBER_ID,
                "EMPLOYMENT",
                "{\"3\":\"LV2\",\"5\":\"LV1\"}",
                "[\"java\",\"python3\"]",
                "[\"EVENING\"]",
                4L,
                NOW_MICROS
            );
    }

    @Test
    @DisplayName("학습 목표가 없고 목록이 비어 있으면 미입력으로 보고 null로 교체를 요청한다")
    void consume_nullGoalAndEmptyLists_upsertsNulls() {
        // given
        given(memberReplicaRepository.findByMemberIdWithForShare(MEMBER_ID)).willReturn(Optional.empty());

        // when
        service.consume(payload(null, List.of(), List.of(), List.of()));

        // then
        verify(learningProfileReplicaRepository)
            .upsertFromLearningProfileUpdated(MEMBER_ID, null, null, null, null, 4L, NOW_MICROS);
    }

    @Test
    @DisplayName("같은 태그 ID가 두 번 오면 마지막 레벨로 교체를 요청한다")
    void consume_duplicateTagId_usesLatestLevel() {
        // given
        given(memberReplicaRepository.findByMemberIdWithForShare(MEMBER_ID)).willReturn(Optional.empty());

        // when
        service
            .consume(
                payload(
                    "STUDY",
                    List.of("cpp"),
                    List.of("NIGHT"),
                    List.of(new TagLevel("3", "LV1"), new TagLevel("3", "LV2"))
                )
            );

        // then
        verify(learningProfileReplicaRepository)
            .upsertFromLearningProfileUpdated(
                MEMBER_ID,
                "STUDY",
                "{\"3\":\"LV2\"}",
                "[\"cpp\"]",
                "[\"NIGHT\"]",
                4L,
                NOW_MICROS
            );
    }

    @Test
    @DisplayName("활동 중인 회원의 학습 프로필 이벤트는 복제본 교체를 요청한다")
    void consume_activeMember_upserts() {
        // given
        given(memberReplicaRepository.findByMemberIdWithForShare(MEMBER_ID))
            .willReturn(Optional.of(MemberReplica.sync(MEMBER_ID, "닉네임", null, MemberStatus.ACTIVE, 5L, NOW_MICROS)));

        // when
        service.consume(payload("EMPLOYMENT", List.of("java"), List.of("EVENING"), List.of()));

        // then
        verify(learningProfileReplicaRepository)
            .upsertFromLearningProfileUpdated(
                MEMBER_ID,
                "EMPLOYMENT",
                null,
                "[\"java\"]",
                "[\"EVENING\"]",
                4L,
                NOW_MICROS
            );
    }

    @Test
    @DisplayName("탈퇴한 회원의 학습 프로필 이벤트는 소비 기록만 남기고 복제본에 반영하지 않는다")
    void consume_withdrawnMember_skips() {
        // given
        given(memberReplicaRepository.findByMemberIdWithForShare(MEMBER_ID))
            .willReturn(
                Optional.of(MemberReplica.sync(MEMBER_ID, "탈퇴회원", null, MemberStatus.WITHDRAWN, 9L, NOW_MICROS))
            );

        // when
        service.consume(payload("EMPLOYMENT", List.of("java"), List.of("EVENING"), List.of()));

        // then
        verify(processedEventStore).insert(any(EventMetadata.class), eq(NOW_MICROS));
        verify(learningProfileReplicaRepository, never())
            .upsertFromLearningProfileUpdated(anyLong(), any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("소비 기록 INSERT가 중복인 학습 프로필 이벤트는 복제본에 반영하지 않고 중복 예외를 전파한다")
    void consume_duplicateEventId_skips() {
        // given
        willThrow(new DuplicateKeyException("duplicate"))
            .given(processedEventStore)
            .insert(any(EventMetadata.class), any(LocalDateTime.class));

        // when & then
        assertThatThrownBy(() -> service.consume(payload("EMPLOYMENT", List.of("java"), List.of("EVENING"), List.of())))
            .isInstanceOf(DuplicateKeyException.class);

        // then
        verify(memberReplicaRepository, never()).findByMemberIdWithForShare(anyLong());
        verify(learningProfileReplicaRepository, never())
            .upsertFromLearningProfileUpdated(anyLong(), any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("계약과 다른 학습 프로필 이벤트는 예외를 던지고 복제본에 반영하지 않는다")
    void consume_invalidPayload_throws() {
        // when & then
        assertThatThrownBy(() -> service.consume(payload("EMPLOYMENT", null, List.of("EVENING"), List.of())))
            .isInstanceOf(IllegalArgumentException.class);
        verify(learningProfileReplicaRepository, never())
            .upsertFromLearningProfileUpdated(anyLong(), any(), any(), any(), any(), anyLong(), any());
    }

    private static LearningProfileUpdatedPayload payload(
        String goal,
        List<String> preferredLanguages,
        List<String> activeTimeSlots,
        List<TagLevel> tagLevels
    ) {
        return new LearningProfileUpdatedPayload(
            EVENT_ID,
            OCCURRED_AT,
            "corr-1",
            String.valueOf(MEMBER_ID),
            4L,
            goal,
            preferredLanguages,
            activeTimeSlots,
            tagLevels
        );
    }
}
