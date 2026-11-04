package com.backtoback.contest.exam.client;

/**
 * 회원 내부 상태 조회에서 기대하는 표시 정보와 원천 순서 스냅샷이다.
 */
public record MemberStatusResponse(
    String memberId,
    String status,
    String role,
    Long sourceVersion,
    String nickname,
    String profileImageUrl
) {
    /**
     * 필수 정보가 없거나 요청한 회원과 다르면 복제본을 저장하지 않는다.
     */
    public void validate(long requestedMemberId) {
        if (!Long.toString(requestedMemberId).equals(memberId) || sourceVersion == null || sourceVersion <= 0
            || nickname == null || nickname.isBlank() || role == null || role.isBlank()
            || !("ACTIVE".equals(status) || "SUSPENDED".equals(status) || "WITHDRAWN".equals(status))) {
            throw new IllegalArgumentException("Invalid member status response");
        }
    }
}
