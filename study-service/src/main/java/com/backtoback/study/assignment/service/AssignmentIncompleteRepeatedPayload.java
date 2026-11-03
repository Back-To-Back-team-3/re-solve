package com.backtoback.study.assignment.service;

import java.util.List;

/**
 * S7 AssignmentIncompleteRepeated 이벤트 페이로드 (이벤트 계약서 §S7).
 * memberIds = 연속 INCOMPLETE 멤버, recipientMemberIds = LEADER·MANAGER.
 */
record AssignmentIncompleteRepeatedPayload(
    String studyId,
    String assignmentId,
    List<Long> memberIds,
    List<Long> recipientMemberIds
) {
}
