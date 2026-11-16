package com.backtoback.study.replica.event.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.backtoback.common.event.dto.EventMetadata;
import com.backtoback.study.replica.domain.MemberStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * member-service 회원 정지·해제 이벤트 페이로드 (event-contract.md §M2 MemberSuspended v1).
 * {@code reason}은 사용자 노출 금지 값이라 받지 않는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MemberSuspendedPayload(
    String eventId,
    String occurredAt,
    String correlationId,
    String memberId,
    Boolean suspended,
    Long sourceVersion
) {

    private static final String EVENT_TYPE = "MemberSuspended";
    private static final String SCHEMA_VERSION = "v1";
    private static final String ANONYMOUS_CORRELATION_ID = "anonymous";

    /**
     * 소비 기록 저장용 메타데이터를 만든다. correlationId가 없으면 {@code anonymous}로 대체한다.
     *
     * @throws IllegalArgumentException eventId·occurredAt·memberId 형식이 계약과 다를 때
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
     * 복제본에 반영하기 전에 필드가 계약을 지키는지 검사한다.
     * 위반 메시지는 재수신 후 DLQ로 격리되도록 예외로 알린다(event-contract.md §1.4 3).
     *
     * @throws IllegalArgumentException 필수값 누락·형식이 계약과 다를 때
     */
    public void validate() {
        if (memberId == null || !memberId.matches("[0-9]+")) {
            throw new IllegalArgumentException("MemberSuspended memberId must be a numeric string");
        }
        if (sourceVersion == null || sourceVersion < 0) {
            throw new IllegalArgumentException("MemberSuspended sourceVersion must be zero or positive");
        }
        if (suspended == null) {
            throw new IllegalArgumentException("MemberSuspended suspended is required");
        }
    }

    public Long memberIdAsLong() {
        return Long.parseLong(memberId);
    }

    /**
     * 정지는 {@code SUSPENDED}, 해제는 {@code ACTIVE}로 바꾼다. 별도 해제 이벤트 없이 {@code suspended = false}로 통합된 계약이다.
     */
    public MemberStatus targetStatus() {
        return Boolean.TRUE.equals(suspended) ? MemberStatus.SUSPENDED : MemberStatus.ACTIVE;
    }
}
