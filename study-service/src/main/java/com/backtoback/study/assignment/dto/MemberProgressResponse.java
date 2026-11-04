package com.backtoback.study.assignment.dto;

import com.backtoback.study.assignment.domain.AssignmentMemberStatus;
import com.backtoback.study.assignment.domain.MemberProgressStatus;

/**
 * 구성원별 과제 진행률 (API 명세서 §5.4.3 memberProgress[]).
 */
public record MemberProgressResponse(
    String memberId,
    String nickname,
    MemberProgressStatus status,
    int completedCount,
    int onTimeCount,
    int activeProblemCount
) {

    public static MemberProgressResponse from(AssignmentMemberStatus ams, String nickname, int activeCount) {
        return new MemberProgressResponse(
            String.valueOf(ams.getMemberId()),
            nickname,
            ams.getStatus(),
            ams.getCompletedCount(),
            ams.getOnTimeCount(),
            activeCount
        );
    }
}
