package com.backtoback.study.replica.event.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.backtoback.common.event.dto.EventMetadata;
import com.backtoback.study.replica.domain.MemberStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * member-service 회원 정보 변경 이벤트 페이로드 (event-contract.md §M1 MemberUpdated v1).
 * 헤더와 페이로드가 한 JSON 객체에 평평하게 오므로 study-service가 쓰는 헤더 필드도 함께 받는다(event-contract.md §1.1).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MemberUpdatedPayload(
    String eventId,
    String occurredAt,
    String correlationId,
    String memberId,
    Long sourceVersion,
    String status,
    String nickname,
    String profileImageUrl
) {

    private static final String EVENT_TYPE = "MemberUpdated";
    private static final String SCHEMA_VERSION = "v1";
    private static final String ANONYMOUS_CORRELATION_ID = "anonymous";
    // member_replicas 컬럼 길이 (ERD 3.5 member_replicas)
    private static final int NICKNAME_MAX_LENGTH = 50;
    private static final int PROFILE_IMAGE_URL_MAX_LENGTH = 500;

    /**
     * 소비 기록 저장용 메타데이터를 만든다. correlationId가 없으면 {@code anonymous}로 대체한다.
     * 헤더 형식이 계약과 다르면 예외 종류와 관계없이 롤백 후 재수신을 거쳐 DLQ로 격리된다(event-contract.md §1.4 3).
     *
     * @throws IllegalArgumentException eventId가 UUID v4가 아니거나 memberId·correlationId가 헤더 규칙과 다를 때
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
     * 복제본에 반영하기 전에 필드가 계약과 컬럼 제약을 지키는지 검사한다.
     * 위반 메시지는 재수신 후 DLQ로 격리되도록 예외로 알린다(event-contract.md §1.4 3).
     *
     * @throws IllegalArgumentException 필수값 누락·형식·길이가 계약과 다를 때
     */
    public void validate() {
        // 닉네임·이미지 주소는 회원 정보이므로 예외 메시지에 값을 넣지 않는다.
        if (memberId == null || !memberId.matches("[0-9]+")) {
            throw new IllegalArgumentException("MemberUpdated memberId must be a numeric string");
        }
        if (sourceVersion == null || sourceVersion < 0) {
            throw new IllegalArgumentException("MemberUpdated sourceVersion must be zero or positive");
        }
        if (nickname == null || nickname.isBlank() || nickname.length() > NICKNAME_MAX_LENGTH) {
            throw new IllegalArgumentException("MemberUpdated nickname is blank or exceeds its column length");
        }
        if (profileImageUrl != null && profileImageUrl.length() > PROFILE_IMAGE_URL_MAX_LENGTH) {
            throw new IllegalArgumentException("MemberUpdated profileImageUrl exceeds its column length");
        }
        memberStatus();
    }

    public Long memberIdAsLong() {
        return Long.parseLong(memberId);
    }

    /**
     * 계약의 상태 문자열을 복제본 상태로 바꾼다.
     *
     * @throws IllegalArgumentException 계약에 없는 상태 값일 때
     */
    public MemberStatus memberStatus() {
        if (status == null) {
            throw new IllegalArgumentException("MemberUpdated status is required");
        }
        return MemberStatus.valueOf(status);
    }
}
