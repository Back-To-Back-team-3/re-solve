package com.backtoback.study.membership.service;

/**
 * S3 StudyApplicationDecided 이벤트 페이로드 (이벤트 계약서 §4.5 S3).
 * 모든 ID는 문자열, decidedAt은 이벤트 계약서 §1.2 표기 규칙의 ISO-8601 형식.
 */
record StudyApplicationDecidedPayload(
    String studyId,
    String studyName,
    String membershipId,
    String memberId,
    String result,
    String decidedAt
) {
}
