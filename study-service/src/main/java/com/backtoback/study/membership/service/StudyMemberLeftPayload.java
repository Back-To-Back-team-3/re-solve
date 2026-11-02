package com.backtoback.study.membership.service;

/**
 * S2 StudyMemberLeft 이벤트 페이로드 (이벤트 계약서 §4.5 S2).
 * 모든 ID는 문자열, leftAt은 이벤트 계약서 §1.2 표기 규칙의 ISO-8601 형식.
 */
record StudyMemberLeftPayload(
    String studyId,
    String membershipId,
    String memberId,
    String reason,
    String leftAt,
    String studyName
) {
}
