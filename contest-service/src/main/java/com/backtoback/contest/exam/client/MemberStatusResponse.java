package com.backtoback.contest.exam.client;

/**
 * 회원 내부 상태와 선택적인 표시 정보 스냅샷이다.
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
     * 회원 상태 조회의 필수 필드와 요청한 회원의 일치를 확인한다.
     */
    public void validate(long requestedMemberId) {
        if (!Long.toString(requestedMemberId).equals(memberId) || !("USER".equals(role) || "ADMIN".equals(role))
            || !("ACTIVE".equals(status) || "SUSPENDED".equals(status) || "WITHDRAWN".equals(status))) {
            throw new IllegalArgumentException("Invalid member status response");
        }
    }

    /** 버전이 있는 표시 정보만 복제본 갱신에 사용한다. */
    public void validateSnapshot(long requestedMemberId) {
        validate(requestedMemberId);
        if (sourceVersion == null || sourceVersion <= 0 || nickname == null || nickname.isBlank()) {
            throw new IllegalArgumentException("Invalid member snapshot response");
        }
    }

    /** 표시 정보 확장이 일부라도 포함됐는지 확인한다. */
    public boolean hasSnapshotFields() {
        return sourceVersion != null || nickname != null || profileImageUrl != null;
    }
}
