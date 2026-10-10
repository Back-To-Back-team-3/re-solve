package com.backtoback.contest.exam.client;

import java.util.List;

/**
 * 생성 권한 확인에 필요한 스터디 상태·구성원 목록 조회 경계다.
 * 참가 자격은 이 조회로 대체하지 않고 후속 구현에서 승인 구성원 복제본으로 판단한다.
 * <p>기준 문서: API 명세서 v1.1 / §5.7.1 스터디 구성원 목록 조회 (내부): 상태·역할 계약.
 */
public interface StudyClient {
    /**
     * 스터디 상태와 구성원 역할을 조회한다. 외부 HTTP·실제 권한 판정은 후속 작업이다.
     *
     * @param studyId 시험 생성 대상 스터디 ID
     * @return 스터디 상태와 구성원 목록
     */
    Members getMembers(String studyId);

    /**
     * 스터디 서비스의 구성원 조회 결과를 표현한다.
     *
     * @param studyId 스터디 ID
     * @param studyStatus ACTIVE·CLOSED 등 스터디 상태
     * @param members 승인된 구성원의 역할 목록
     */
    record Members(String studyId, String studyStatus, List<Member> members) {
    }

    /**
     * 구성원의 식별자와 생성 권한 확인용 역할을 표현한다.
     *
     * @param memberId 회원 ID
     * @param membershipId 가입 ID
     * @param role LEADER·MANAGER·MEMBER
     */
    record Member(String memberId, String membershipId, String role) {
    }
}
