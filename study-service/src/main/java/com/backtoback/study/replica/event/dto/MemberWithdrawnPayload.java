package com.backtoback.study.replica.event.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.backtoback.common.event.dto.EventMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * member-service 회원 탈퇴 도메인 이벤트 페이로드 (event-contract.md > §M3 MemberWithdrawn v1).
 * study-service 소비 필드만 포함하며, 나머지 필드는 ignoreUnknown으로 무시한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MemberWithdrawnPayload(String eventId, String memberId, String withdrawnAt, long sourceVersion) {

    public EventMetadata toMetadata() {
        OffsetDateTime odt = OffsetDateTime.parse(withdrawnAt);
        return new EventMetadata(UUID.fromString(eventId), "MemberWithdrawn", "v1", odt, memberId, eventId);
    }
}
