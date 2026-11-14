package com.backtoback.study.membership.repository.projection;

/**
 * {@code study_memberships}와 {@code studies} 테이블을 조인해 studyStatus를 함께 조회하는 native query projection (API 명세서 §5.7.2).
 * {@code StudyMembership}에 {@code studies}로의 연관이 없으므로 native query + projection 조합을 사용한다.
 */
public interface MembershipWithStudyStatus {

    Long getId();

    Long getStudyId();

    String getRole();

    String getStudyStatus();
}
