package com.backtoback.study.replica.event.dto;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.backtoback.common.event.dto.EventMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * member-service 학습 프로필 변경 이벤트 페이로드 (event-contract.md §M4 LearningProfileUpdated v1).
 * 회원의 학습 프로필 전체 스냅샷이며, 헤더와 페이로드가 한 JSON 객체에 평평하게 오므로 study-service가 쓰는 헤더 필드도
 * 함께 받는다(event-contract.md §1.1). 값 목록은 member가 소유하므로 여기서는 형식만 검사한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LearningProfileUpdatedPayload(
    String eventId,
    String occurredAt,
    String correlationId,
    String memberId,
    Long profileVersion,
    String goal,
    List<String> preferredLanguages,
    List<String> activeTimeSlots,
    List<TagLevel> tagLevels
) {

    private static final String EVENT_TYPE = "LearningProfileUpdated";
    private static final String SCHEMA_VERSION = "v1";
    private static final String ANONYMOUS_CORRELATION_ID = "anonymous";
    // member_learning_profile_replicas.goal 컬럼 길이 (ERD 4.5.10)
    private static final int GOAL_MAX_LENGTH = 30;

    /**
     * 진입한 태그 1개의 레벨.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TagLevel(String tagId, String level) {
    }

    /**
     * 소비 기록 저장용 메타데이터를 만든다. correlationId가 없으면 {@code anonymous}로 대체한다.
     * 헤더 형식이 계약과 다르면 예외 종류와 관계없이 롤백 후 재수신을 거쳐 DLQ로 격리된다(event-contract.md §1.4 3).
     *
     * @throws IllegalArgumentException eventId가 UUID 형식이 아니거나 memberId·correlationId가 헤더 규칙과 다를 때
     * @throws java.time.format.DateTimeParseException occurredAt이 ISO-8601 오프셋 시각이 아닐 때
     * @throws NullPointerException eventId 또는 occurredAt이 없을 때
     */
    public EventMetadata toMetadata() {
        String corrId = (correlationId != null && !correlationId.isBlank()) ? correlationId : ANONYMOUS_CORRELATION_ID;
        return new EventMetadata(
            UUID.fromString(eventId),
            EVENT_TYPE,
            SCHEMA_VERSION,
            OffsetDateTime.parse(occurredAt),
            memberId,
            corrId
        );
    }

    /**
     * 복제본에 반영하기 전에 필수 필드와 형식이 계약과 컬럼 제약을 지키는지 검사한다.
     * {@code goal}은 프로필 미입력이면 없을 수 있어 허용한다(event-contract.md §M4 goal 필수 N).
     *
     * @throws IllegalArgumentException 필수값 누락·형식·길이·태그 ID 중복이 계약과 다를 때
     */
    public void validate() {
        if (memberId == null || !memberId.matches("[0-9]+")) {
            throw new IllegalArgumentException("LearningProfileUpdated memberId must be a numeric string");
        }
        if (profileVersion == null || profileVersion < 1) {
            throw new IllegalArgumentException("LearningProfileUpdated profileVersion must be positive");
        }
        if (goal != null && (goal.isBlank() || goal.length() > GOAL_MAX_LENGTH)) {
            throw new IllegalArgumentException("LearningProfileUpdated goal is blank or exceeds its column length");
        }
        if (preferredLanguages == null || activeTimeSlots == null || tagLevels == null) {
            throw new IllegalArgumentException(
                "LearningProfileUpdated preferredLanguages, activeTimeSlots and tagLevels are required"
            );
        }
        // 진입한 태그 전체를 태그당 하나씩 보내는 스냅샷이라 같은 태그 ID가 두 번 오면 계약 위반이다(event-contract.md §M4 tagLevels).
        Set<String> tagIds = new HashSet<>();
        for (TagLevel tagLevel : tagLevels) {
            if (tagLevel == null || isBlank(tagLevel.tagId()) || isBlank(tagLevel.level())) {
                throw new IllegalArgumentException("LearningProfileUpdated tagLevels entry requires tagId and level");
            }
            if (!tagIds.add(tagLevel.tagId())) {
                throw new IllegalArgumentException("LearningProfileUpdated tagLevels has a duplicate tagId");
            }
        }
    }

    public Long memberIdAsLong() {
        return Long.parseLong(memberId);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
