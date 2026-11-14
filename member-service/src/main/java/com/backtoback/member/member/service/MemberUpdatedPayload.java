package com.backtoback.member.member.service;

import com.backtoback.member.member.domain.Member;

/**
 * {@code MemberUpdated} v1 업무 필드 (이벤트 계약 M1). 공통 헤더는 {@code OutboxEventWriter}가 붙인다.
 */
public record MemberUpdatedPayload(
    String memberId,
    long sourceVersion,
    String status,
    String nickname,
    String profileImageUrl
) {

    static MemberUpdatedPayload from(Member member) {
        return new MemberUpdatedPayload(
            String.valueOf(member.getId()),
            member.getProfileVersion(),
            member.getStatus().name(),
            member.getNickname(),
            member.getProfileImageUrl()
        );
    }
}
