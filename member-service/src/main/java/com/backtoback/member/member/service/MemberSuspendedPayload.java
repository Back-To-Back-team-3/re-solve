package com.backtoback.member.member.service;

import com.backtoback.member.member.domain.Member;

/**
 * {@code MemberSuspended} v1 업무 필드 (이벤트 계약 M2). 공통 헤더는 {@code OutboxEventWriter}가 붙인다.
 */
public record MemberSuspendedPayload(
    String memberId,
    boolean suspended,
    long sourceVersion,
    String reason,
    String changedAt
) {

    static MemberSuspendedPayload of(Member member, String reason, String changedAt) {
        return new MemberSuspendedPayload(
            String.valueOf(member.getId()),
            member.isSuspended(),
            member.getProfileVersion(),
            reason,
            changedAt
        );
    }
}
