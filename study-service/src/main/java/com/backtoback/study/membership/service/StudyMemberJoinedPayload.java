package com.backtoback.study.membership.service;

/**
 * S1 StudyMemberJoined 이벤트 페이로드 (이벤트 계약서 §4.5 S1).
 * 모든 ID는 문자열, joinedAt은 이벤트 계약서 §1.2 표기 규칙의 ISO-8601 형식.
 */
record StudyMemberJoinedPayload(String studyId, String membershipId, String memberId, String role, String joinedAt) {
}
