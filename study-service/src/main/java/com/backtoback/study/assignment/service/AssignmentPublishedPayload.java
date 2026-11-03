package com.backtoback.study.assignment.service;

import java.util.List;

/**
 * S5 AssignmentPublished 이벤트 페이로드 (이벤트 계약서 §S5).
 * 모든 ID는 문자열, startsAt·deadlineAt은 이벤트 계약서 §1.2 ISO-8601 형식.
 */
record AssignmentPublishedPayload(
    String studyId,
    String assignmentId,
    String title,
    String startsAt,
    String deadlineAt,
    List<Long> targetMemberIds
) {
}
