package com.backtoback.member.skill.service;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * {@code SubmissionJudged} v1(이벤트 계약 J1)에서 성취도 계산에 쓰는 값.
 *
 * @param difficulty 1~3, 회차 스냅샷
 * @param tagIds 회차 스냅샷의 태그 ID
 */
public record PracticeResult(
    long submissionId,
    long memberId,
    long problemId,
    int difficulty,
    String verdict,
    int judgeAttempt,
    LocalDateTime submittedAt,
    Set<Long> tagIds
) {

    public boolean accepted() {
        return "AC".equals(verdict);
    }
}
