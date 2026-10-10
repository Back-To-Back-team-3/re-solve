package com.backtoback.contest.exam.client;

/**
 * 회원 상태 조회의 서비스 간 호출 경계다. 복제본이 없을 때 호출하는 후속 구현에 사용한다.
 * 이 Issue에서는 HTTP 호출·정지 판정을 구현하지 않는다.
 * <p>기준 문서: API 명세서 v1.1 / §1.7.1 회원 상태 조회 (내부): 회원 상태·역할 조회.
 */
public interface MemberClient {
    /**
     * 회원의 현재 상태를 조회한다. 조회 실패를 ACTIVE로 간주해서는 안 된다.
     *
     * @param memberId 인증 정보 또는 확인한 복제본의 회원 ID
     * @return 회원 ID·상태·역할
     */
    MemberStatus getStatus(String memberId);

    /**
     * 회원 서비스가 소유한 상태·역할의 문자열 계약을 유지한다.
     *
     * @param memberId 회원 ID
     * @param status ACTIVE·SUSPENDED 등 회원 상태
     * @param role USER·ADMIN 등 회원 역할
     */
    record MemberStatus(String memberId, String status, String role) {
    }
}
