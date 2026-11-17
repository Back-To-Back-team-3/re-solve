package com.backtoback.study.membership.client;

import com.backtoback.study.replica.domain.MemberStatus;

/**
 * member 내부 상태 조회 응답 (API 명세서 1.7.1). 내부 API라 공통 응답으로 감싸지 않는다.
 * 표시 정보와 원천 버전이 없으므로 회원 복제본을 만들거나 갱신하는 데 쓰지 않는다 (API 명세서 4.8.8).
 */
public record MemberStatusResponse(String memberId, String status, String role) {

    /**
     * 요청한 회원의 응답인지와 상태 값이 계약 범위인지 확인한다. {@code role}은 정지 판정에 쓰지 않아 확인하지 않는다.
     *
     * @return 확인한 회원 상태
     * @throws IllegalArgumentException 회원 ID가 다르거나 상태 값이 계약 밖인 경우
     */
    public MemberStatus validatedStatus(long requestedMemberId) {
        if (!Long.toString(requestedMemberId).equals(memberId)) {
            throw new IllegalArgumentException("Member status response for another member");
        }
        if (status == null) {
            throw new IllegalArgumentException("Member status response without status");
        }
        return MemberStatus.valueOf(status);
    }
}
